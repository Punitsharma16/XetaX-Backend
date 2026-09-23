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
    private final com.xetax.crm.realtime.RealtimeHub realtimeHub;
    private final org.springframework.beans.factory.ObjectProvider<
            com.xetax.crm.whatsapp.service.WhatsAppFlowService> flowServiceProvider;
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
    private final com.xetax.crm.whatsapp.service.WhatsAppMediaService mediaService;

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

    /**
     * Records what Meta says it charged for this message, straight from the
     * webhook's pricing object.
     *
     * <p>Taken from Meta rather than worked out here, because from 1 October
     * 2026 the category stops being enough on its own: a service message still
     * arrives as category "service" while billable flips from false to true
     * and the type from "free_customer_service" to "regular". Code that reads
     * the category alone goes on calling those messages free.
     *
     * @return whether anything was actually recorded
     */
    private boolean applyPricing(JsonNode pricing, WhatsAppMessage message) {
        if (pricing == null || pricing.isMissingNode() || pricing.isNull()) return false;

        boolean changed = false;
        if (pricing.hasNonNull("billable")) {
            message.setPricingBillable(pricing.path("billable").asBoolean());
            changed = true;
        }
        if (pricing.hasNonNull("category")) {
            message.setPricingCategory(pricing.path("category").asText());
            changed = true;
        }
        if (pricing.hasNonNull("type")) {
            message.setPricingType(pricing.path("type").asText());
            changed = true;
        }
        if (pricing.hasNonNull("pricing_model")) {
            message.setPricingModel(pricing.path("pricing_model").asText());
            changed = true;
        }
        return changed;
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
            // Pricing first, and outside the staleness check: Meta attaches it
            // to whichever status it likes (often "sent"), so a DLR that moves
            // the status nowhere can still be the one carrying what we were
            // charged. Dropping it as "stale" would lose the figure for good.
            boolean pricingChanged = applyPricing(status.path("pricing"), message);

            if (RANK.get(newStatus) <= RANK.get(message.getStatus())) {
                if (pricingChanged) messageRepository.save(message);
                return; // stale or duplicate DLR — nothing else to do
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
            // A document arrives with the name the customer's file had. Keep it:
            // it is what the thread shows and what the download link serves.
            String mediaFilename = "document".equals(type)
                    ? trimmedOrNull(inbound.path("document").path("filename").asText("")) : null;
            String body = inboundText(type, inbound, mediaFilename);

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
                    .mediaFilename(mediaFilename)
                    .referralSource(referralSourceOf(inbound))
                    .providerMessageId(wamid)
                    .status(WhatsAppMessageStatus.DELIVERED)
                    .deliveredAt(timestampOf(inbound))
                    .build();
            messageRepository.save(message);

            // Meta hands us an id, not a file, and the download URL behind it
            // expires within minutes — so fetch it now. The shareable link is
            // minted by WhatsAppMediaService once the bytes are stored.
            mediaService.fetchAfterCommit(config, message.getId(), mediaId);

            conversation.setLastMessage(body.length() > 500 ? body.substring(0, 500) : body);
            conversation.setLastMessageAt(Instant.now());
            conversation.setLastInboundAt(timestampOf(inbound));
            conversation.setUnreadCount(conversation.getUnreadCount() + 1);
            conversationRepository.save(conversation);

            // Bell: owner sees every incoming customer message.
            notificationService.push(config.getOwnerUserId(), config.getOwnerUserId(),
                    "WHATSAPP_INBOUND",
                    "WhatsApp: " + (contactName != null && !contactName.isBlank() ? contactName : from),
                    body, "/app/whatsapp/conversations");

            // Wake any open inbox on this workspace. The bell notification
            // above is personal to the owner; this one is the workspace event
            // every team member's inbox listens for.
            realtimeHub.publishAfterCommit(config.getOwnerUserId(), "whatsapp.inbound",
                    Map.of("conversationId", conversation.getId()));

            // A submitted Flow is a form, not a chat line: the answers go to the
            // Flow service, which stores them and may open a CRM record.
            if ("interactive".equals(type)
                    && "nfm_reply".equals(inbound.path("interactive").path("type").asText(""))) {
                JsonNode reply = inbound.path("interactive").path("nfm_reply");
                String responseJson = reply.path("response_json").asText("");
                try {
                    flowServiceProvider.getObject().recordSubmission(config.getOwnerUserId(),
                            flowTokenOf(responseJson), from, conversation.getId(), responseJson);
                } catch (Exception e) {
                    log.warn("Flow submission could not be stored: {}", e.getMessage());
                }
            }

            // Bot desk (AI autopilot / live hand-off) reacts off-thread.
            try {
                eventPublisher.publishEvent(new WhatsAppInboundEvent(config, conversation, message));
            } catch (Exception e) {
                log.warn("Inbound event publish failed: {}", e.getMessage());
            }
        }
    }

    /**
     * What the customer actually tapped, not the envelope it came in.
     *
     * <p>An interactive reply arrives as a small object naming its own shape:
     * a quick-reply button, a row picked from a list, or a submitted Flow. The
     * whole node used to be stored verbatim, so the inbox showed a customer's
     * "Yes" as a line of raw JSON. The title is what they saw on their phone,
     * so that is what the thread shows. Anything unrecognised still falls back
     * to the raw node — better an ugly line than a silently empty message.
     */
    private static String interactiveText(JsonNode interactive) {
        if (interactive == null || interactive.isMissingNode()) return "";
        String type = interactive.path("type").asText("");
        JsonNode reply = interactive.path(type);

        String title = reply.path("title").asText("");
        if (!title.isBlank()) return title;

        // A submitted Flow: Meta's body is only the word "Sent", so the thread
        // shows what the customer actually filled in.
        if ("nfm_reply".equals(type)) {
            String answers = flowAnswersText(reply.path("response_json").asText(""));
            if (answers != null) return answers;
        }

        // A submitted Flow carries a human-readable body plus its answers.
        String body = reply.path("body").asText("");
        if (!body.isBlank()) return body;

        return interactive.toString();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper ANSWERS = new com.fasterxml.jackson.databind.ObjectMapper();

    /** "Form submitted" and one "key: value" line per answer; null when there is nothing to show. */
    static String flowAnswersText(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) return null;
        try {
            StringBuilder text = new StringBuilder("Form submitted");
            int shown = 0;
            var fields = ANSWERS.readTree(responseJson).properties();
            for (var entry : fields) {
                if ("flow_token".equals(entry.getKey())) continue;
                JsonNode value = entry.getValue();
                String shownValue = value.isArray()
                        ? String.join(", ", ANSWERS.convertValue(value, String[].class))
                        : value.isValueNode() ? value.asText() : value.toString();
                if (shownValue.isBlank()) continue;
                text.append('\n').append(entry.getKey()).append(": ").append(shownValue);
                shown++;
            }
            if (shown == 0) return "Form submitted";
            String out = text.toString();
            return out.length() > 3900 ? out.substring(0, 3900) + "…" : out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The token we minted when the Flow was sent. Meta echoes it inside the
     * submitted payload, and it is the only link back to which customer and
     * which send this form belongs to.
     */
    private String flowTokenOf(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) return null;
        try {
            String token = objectMapper.readTree(responseJson).path("flow_token").asText("");
            return token.isBlank() ? null : token;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One readable line for any message Meta can deliver.
     *
     * <p>Each branch is a shape a customer can genuinely send. Without them a
     * thread shows an empty bubble (location, contacts, order) or a blob of
     * raw JSON. A media message shows its caption when there is one, because
     * the file itself now travels alongside as a link — the bracketed label is
     * only the fallback for a caption-less file.
     */
    private String inboundText(String type, JsonNode inbound, String mediaFilename) {
        return switch (type) {
            case "text" -> inbound.path("text").path("body").asText("");
            case "button" -> inbound.path("button").path("text").asText("");
            case "interactive" -> interactiveText(inbound.path("interactive"));
            case "image" -> captionOr(inbound.path("image"), "[photo]");
            case "video" -> captionOr(inbound.path("video"), "[video]");
            case "audio" -> inbound.path("audio").path("voice").asBoolean(false)
                    ? "[voice message]" : "[audio]";
            case "sticker" -> "[sticker]";
            case "document" -> {
                String caption = inbound.path("document").path("caption").asText("");
                if (!caption.isBlank()) yield caption;
                yield mediaFilename == null ? "[document]" : mediaFilename;
            }
            case "location" -> locationText(inbound.path("location"));
            case "contacts" -> contactsText(inbound.path("contacts"));
            case "reaction" -> reactionText(inbound.path("reaction"));
            case "order" -> orderText(inbound.path("order"));
            case "system" -> {
                String systemBody = inbound.path("system").path("body").asText("");
                yield systemBody.isBlank() ? "[system message]" : systemBody;
            }
            case "unsupported" -> {
                String reason = inbound.path("errors").path(0).path("title").asText("");
                yield reason.isBlank() ? "[unsupported message]" : "[unsupported] " + reason;
            }
            default -> "[" + type + " message]";
        };
    }

    /**
     * A customer who tapped a click-to-WhatsApp ad or a Page button arrives with
     * a referral block. Its source_type is "ad" or "post"; an ad click id alone
     * still means an ad.
     */
    static String referralSourceOf(JsonNode inbound) {
        JsonNode referral = inbound.path("referral");
        if (referral.isMissingNode() || referral.isNull() || !referral.isObject() || referral.isEmpty()) return null;
        String type = referral.path("source_type").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        if (!type.isEmpty()) return type.length() > 16 ? type.substring(0, 16) : type;
        return referral.hasNonNull("ctwa_clid") ? "ad" : "referral";
    }

    private static String captionOr(JsonNode media, String fallback) {
        String caption = media.path("caption").asText("");
        return caption.isBlank() ? fallback : caption;
    }

    /** A pin shows its place when the customer named one, else its coordinates. */
    private static String locationText(JsonNode location) {
        String name = location.path("name").asText("");
        String address = location.path("address").asText("");
        String where = !name.isBlank() && !address.isBlank() ? name + ", " + address
                : !name.isBlank() ? name
                : !address.isBlank() ? address
                : location.path("latitude").asText("") + "," + location.path("longitude").asText("");
        return "[location] " + where;
    }

    /** A shared contact card: the names on it, not the vCard. */
    private static String contactsText(JsonNode contacts) {
        StringBuilder names = new StringBuilder();
        for (JsonNode contact : contacts) {
            String name = contact.path("name").path("formatted_name").asText("");
            if (name.isBlank()) continue;
            if (!names.isEmpty()) names.append(", ");
            names.append(name);
        }
        return names.isEmpty() ? "[contact]" : "[contact] " + names;
    }

    /** An emoji reaction to an earlier message; an empty emoji means removed. */
    private static String reactionText(JsonNode reaction) {
        String emoji = reaction.path("emoji").asText("");
        return emoji.isBlank() ? "[reaction removed]" : "Reacted " + emoji;
    }

    /** A cart sent from a catalogue. */
    private static String orderText(JsonNode order) {
        int items = order.path("product_items").size();
        return items == 0 ? "[order]" : "[order] " + items + (items == 1 ? " item" : " items");
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private WhatsAppMessageType mapType(String type) {
        return switch (type) {
            case "image" -> WhatsAppMessageType.IMAGE;
            case "document" -> WhatsAppMessageType.DOCUMENT;
            case "video" -> WhatsAppMessageType.VIDEO;
            case "audio" -> WhatsAppMessageType.AUDIO;
            case "sticker" -> WhatsAppMessageType.STICKER;
            case "interactive", "button" -> WhatsAppMessageType.INTERACTIVE;
            case "reaction" -> WhatsAppMessageType.REACTION;
            case "location" -> WhatsAppMessageType.LOCATION;
            case "contacts" -> WhatsAppMessageType.CONTACTS;
            case "order" -> WhatsAppMessageType.ORDER;
            case "system" -> WhatsAppMessageType.SYSTEM;
            case "unsupported" -> WhatsAppMessageType.UNSUPPORTED;
            default -> WhatsAppMessageType.TEXT;
        };
    }

    private Instant timestampOf(JsonNode node) {
        long epoch = node.path("timestamp").asLong(0);
        return epoch > 0 ? Instant.ofEpochSecond(epoch) : Instant.now();
    }
}
