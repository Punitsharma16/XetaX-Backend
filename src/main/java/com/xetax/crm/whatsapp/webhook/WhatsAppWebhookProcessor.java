package com.xetax.crm.whatsapp.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppConversation;
import com.xetax.crm.whatsapp.entity.WhatsAppMessage;
import com.xetax.crm.whatsapp.enums.MessageDirection;
import com.xetax.crm.whatsapp.enums.RecipientStatus;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageStatus;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageType;
import com.xetax.crm.whatsapp.entity.WhatsAppTemplate;
import com.xetax.crm.whatsapp.repository.*;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Applies one Meta webhook "value" object (statuses / inbound messages).
 * Fully idempotent: status updates are keyed by providerMessageId and only
 * move FORWARD (sent → delivered → read), inbound messages dedupe on the
 * unique providerMessageId column — so Kafka redeliveries and Meta webhook
 * retries are both harmless.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppWebhookProcessor {

    private final com.xetax.crm.notification.NotificationService notificationService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    private static final Map<WhatsAppMessageStatus, Integer> RANK = Map.of(
            WhatsAppMessageStatus.QUEUED, 0,
            WhatsAppMessageStatus.SENT, 1,
            WhatsAppMessageStatus.DELIVERED, 2,
            WhatsAppMessageStatus.READ, 3,
            WhatsAppMessageStatus.FAILED, 9
    );

    private final WhatsAppMessageRepository messageRepository;
    private final WhatsAppConversationRepository conversationRepository;
    private final WhatsAppConfigRepository configRepository;
    private final WhatsAppTemplateRepository templateRepository;
    private final WhatsAppCampaignRepository campaignRepository;
    private final WhatsAppCampaignRecipientRepository recipientRepository;
    private final WhatsAppMessagingService messagingService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public void processRawValue(String valueJson) {
        try {
            meterRegistry.counter("whatsapp.webhook.received").increment();
            processValue(objectMapper.readTree(valueJson));
        } catch (Exception e) {
            // never throw — a poison event must not wedge the consumer/webhook
            meterRegistry.counter("whatsapp.webhook.failed").increment();
            log.error("WhatsApp webhook event processing failed: {}", e.getMessage());
        }
    }

    @Transactional
    public void processValue(JsonNode value) {
        for (JsonNode status : value.path("statuses")) {
            applyStatus(status);
        }
        JsonNode messages = value.path("messages");
        if (!messages.isMissingNode() && messages.isArray() && !messages.isEmpty()) {
            String phoneNumberId = value.path("metadata").path("phone_number_id").asText(null);
            applyInbound(phoneNumberId, value);
        }
    }

    /**
     * Meta's message_template_status_update webhook — approval decisions land
     * here the moment reviewers act, so the panel shows APPROVED/REJECTED
     * without the user pressing Sync. Idempotent: same-status updates no-op.
     */
    @Transactional
    public void processTemplateStatusRaw(String wabaId, String valueJson) {
        try {
            JsonNode value = objectMapper.readTree(valueJson);
            String event = value.path("event").asText("");
            String name = value.path("message_template_name").asText("");
            String language = value.path("message_template_language").asText("en");
            if (event.isBlank() || name.isBlank() || wabaId == null || wabaId.isBlank()) return;

            configRepository.findFirstByWabaId(wabaId).ifPresent(config ->
                    templateRepository.findByWhatsappConfigIdAndNameAndLanguage(
                                    config.getId(), name, language)
                            .ifPresent(template -> {
                                if (event.equals(template.getStatus())) return;
                                template.setStatus(event);
                                template.setRejectionReason(
                                        value.path("reason").isMissingNode()
                                                || value.path("reason").isNull()
                                            ? null : value.path("reason").asText());
                                template.setSyncedAt(Instant.now());
                                templateRepository.save(template);
                                log.info("Template '{}' ({}) -> {}", name, language, event);
                            }));
        } catch (Exception e) {
            log.error("Template status webhook processing failed: {}", e.getMessage());
        }
    }

    /* ------------------------------------------------------- status (DLR) */

    private void applyStatus(JsonNode status) {
        String wamid = status.path("id").asText(null);
        String statusText = status.path("status").asText("");
        if (wamid == null || statusText.isEmpty()) return;

        WhatsAppMessageStatus newStatus = switch (statusText) {
            case "sent" -> WhatsAppMessageStatus.SENT;
            case "delivered" -> WhatsAppMessageStatus.DELIVERED;
            case "read" -> WhatsAppMessageStatus.READ;
            case "failed" -> WhatsAppMessageStatus.FAILED;
            default -> null;
        };
        if (newStatus == null) return;

        messageRepository.findByProviderMessageId(wamid).ifPresent(message -> {
            if (RANK.get(newStatus) <= RANK.get(message.getStatus())) {
                return; // stale or duplicate DLR — ignore
            }
            boolean wasDelivered = RANK.get(message.getStatus())
                    >= RANK.get(WhatsAppMessageStatus.DELIVERED);
            message.setStatus(newStatus);
            Instant when = timestampOf(status);
            switch (newStatus) {
                case DELIVERED -> message.setDeliveredAt(when);
                case READ -> {
                    if (message.getDeliveredAt() == null) message.setDeliveredAt(when);
                    message.setReadAt(when);
                }
                case FAILED -> {
                    JsonNode error = status.path("errors").path(0);
                    message.setErrorCode(error.path("code").asText(null));
                    message.setErrorMessage(error.path("title").asText("Delivery failed"));
                }
                default -> { }
            }
            messageRepository.save(message);

            if (message.getCampaignId() != null) {
                switch (newStatus) {
                    case DELIVERED -> campaignRepository.markOneDelivered(message.getCampaignId());
                    case READ -> {
                        if (!wasDelivered) campaignRepository.markOneDelivered(message.getCampaignId());
                        campaignRepository.markOneRead(message.getCampaignId());
                    }
                    default -> { }
                }
            }
        });

        recipientRepository.findByProviderMessageId(wamid).ifPresent(recipient -> {
            RecipientStatus recipientStatus = switch (newStatus) {
                case DELIVERED -> RecipientStatus.DELIVERED;
                case READ -> RecipientStatus.READ;
                case FAILED -> RecipientStatus.FAILED;
                default -> null;
            };
            if (recipientStatus != null && recipient.getStatus() != RecipientStatus.READ) {
                recipient.setStatus(recipientStatus);
                if (recipientStatus == RecipientStatus.FAILED) {
                    JsonNode error = status.path("errors").path(0);
                    recipient.setError(error.path("title").asText("Delivery failed"));
                }
                recipientRepository.save(recipient);
            }
        });
    }

    /* --------------------------------------------------- inbound messages */

    private void applyInbound(String phoneNumberId, JsonNode value) {
        WhatsAppConfig config = phoneNumberId == null ? null
                : configRepository.findFirstByPhoneNumberId(phoneNumberId).orElse(null);
        if (config == null) {
            log.warn("Inbound WhatsApp message for unknown phone_number_id {}", phoneNumberId);
            return;
        }

        String contactName = value.path("contacts").path(0).path("profile").path("name").asText(null);

        for (JsonNode inbound : value.path("messages")) {
            String wamid = inbound.path("id").asText(null);
            if (wamid == null || messageRepository.existsByProviderMessageId(wamid)) {
                continue; // duplicate delivery
            }
            String from = inbound.path("from").asText("");
            String type = inbound.path("type").asText("text");
            String mediaId = switch (type) {
                case "image", "video", "audio", "document", "sticker" ->
                        inbound.path(type).path("id").asText(null);
                default -> null;
            };
            String body = switch (type) {
                case "text" -> inbound.path("text").path("body").asText("");
                case "button" -> inbound.path("button").path("text").asText("");
                case "interactive" -> inbound.path("interactive").toString();
                case "image", "video", "audio", "document", "sticker" -> {
                    String caption = inbound.path(type).path("caption").asText("");
                    yield caption.isBlank() ? "[" + type + "]" : "[" + type + "] " + caption;
                }
                case "location" -> "[location] " + inbound.path("location").path("latitude").asText("")
                        + "," + inbound.path("location").path("longitude").asText("");
                default -> "[" + type + " message]";
            };

            WhatsAppConversation conversation =
                    messagingService.upsertConversation(config, from, contactName);

            WhatsAppMessage message = WhatsAppMessage.builder()
                    .ownerUserId(config.getOwnerUserId())
                    .whatsappConfigId(config.getId())
                    .conversationId(conversation.getId())
                    .toPhone(from)
                    .direction(MessageDirection.INBOUND)
                    .messageType(mapType(type))
                    .body(body)
                    .mediaId(mediaId)
                    .providerMessageId(wamid)
                    .status(WhatsAppMessageStatus.DELIVERED)
                    .deliveredAt(timestampOf(inbound))
                    .build();
            messageRepository.save(message);

            conversation.setLastMessage(body.length() > 500 ? body.substring(0, 500) : body);
            conversation.setLastMessageAt(Instant.now());
            conversation.setLastInboundAt(timestampOf(inbound));
            conversation.setUnreadCount(conversation.getUnreadCount() + 1);
            conversationRepository.save(conversation);

            // Bell: owner sees every incoming customer message.
            notificationService.push(config.getOwnerUserId(), config.getOwnerUserId(),
                    "WHATSAPP_INBOUND",
                    "WhatsApp: " + (contactName != null && !contactName.isBlank() ? contactName : from),
                    body, "/app/whatsapp/inbox");

            // Bot desk (AI autopilot / live hand-off) reacts off-thread.
            try {
                eventPublisher.publishEvent(new WhatsAppInboundEvent(config, conversation, message));
            } catch (Exception e) {
                log.warn("Inbound event publish failed: {}", e.getMessage());
            }
        }
    }

    private WhatsAppMessageType mapType(String type) {
        return switch (type) {
            case "image" -> WhatsAppMessageType.IMAGE;
            case "document" -> WhatsAppMessageType.DOCUMENT;
            case "video" -> WhatsAppMessageType.VIDEO;
            case "audio" -> WhatsAppMessageType.AUDIO;
            case "interactive", "button" -> WhatsAppMessageType.INTERACTIVE;
            case "reaction" -> WhatsAppMessageType.REACTION;
            default -> WhatsAppMessageType.TEXT;
        };
    }

    private Instant timestampOf(JsonNode node) {
        long epoch = node.path("timestamp").asLong(0);
        return epoch > 0 ? Instant.ofEpochSecond(epoch) : Instant.now();
    }
}
