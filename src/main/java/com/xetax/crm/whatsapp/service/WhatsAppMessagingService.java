package com.xetax.crm.whatsapp.service;

import com.xetax.crm.common.exception.BadRequestException;
import io.micrometer.core.instrument.MeterRegistry;
import com.xetax.crm.common.ratelimit.RateLimiterService;
import com.xetax.crm.data_manager.dto.RecordResponse;
import com.xetax.crm.data_manager.service.RecordService;
import com.xetax.crm.whatsapp.client.WhatsAppSendResult;
import com.xetax.crm.whatsapp.client.WhatsAppSender;
import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import com.xetax.crm.whatsapp.dto.SendMessageRequest;
import com.xetax.crm.whatsapp.dto.WhatsAppMessageResponse;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppConversation;
import com.xetax.crm.whatsapp.entity.WhatsAppMessage;
import com.xetax.crm.whatsapp.enums.MessageDirection;
import com.xetax.crm.whatsapp.enums.WhatsAppConnectionStatus;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageStatus;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageType;
import com.xetax.crm.whatsapp.repository.WhatsAppConfigRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppConversationRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound message pipeline: persist QUEUED → async dispatch on the
 * whatsappExecutor → provider call → SENT/FAILED. The HTTP request never
 * waits on Meta. Rate limiting reuses RateLimiterService per owner.
 */
@Slf4j
@Service
public class WhatsAppMessagingService {

    private final WhatsAppMessageRepository messageRepository;
    private final WhatsAppConversationRepository conversationRepository;
    private final WhatsAppConfigRepository configRepository;
    private final WhatsAppConfigService configService;
    private final WhatsAppSender sender;
    private final PhoneNumberService phoneNumberService;
    private final RateLimiterService rateLimiter;
    private final MetaWhatsAppProperties properties;
    private final RecordService recordService;
    private final MeterRegistry meterRegistry;

    /** Meta's customer-service window: free text only within 24h of the last inbound. */
    private static final java.time.Duration SERVICE_WINDOW = java.time.Duration.ofHours(24);

    /**
     * RecordService is @Lazy to break the startup cycle
     * RecordService -> AutomationEngine -> SendWhatsAppActionExecutor -> this.
     */
    public WhatsAppMessagingService(WhatsAppMessageRepository messageRepository,
                                    WhatsAppConversationRepository conversationRepository,
                                    WhatsAppConfigRepository configRepository,
                                    WhatsAppConfigService configService,
                                    WhatsAppSender sender,
                                    PhoneNumberService phoneNumberService,
                                    RateLimiterService rateLimiter,
                                    MetaWhatsAppProperties properties,
                                    @Lazy RecordService recordService,
                                    MeterRegistry meterRegistry) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.configRepository = configRepository;
        this.configService = configService;
        this.sender = sender;
        this.phoneNumberService = phoneNumberService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.recordService = recordService;
        this.meterRegistry = meterRegistry;
    }

    /** True when a free-form text may be sent to this phone (24h window open). */
    public boolean isWindowOpen(Long configId, String phone) {
        return conversationRepository.findByWhatsappConfigIdAndCustomerPhone(configId, phone)
                .map(c -> windowOpen(c.getLastInboundAt(), Instant.now()))
                .orElse(false);
    }

    /** Pure window rule — separated for unit testing. */
    public static boolean windowOpen(Instant lastInboundAt, Instant now) {
        return lastInboundAt != null && lastInboundAt.plus(SERVICE_WINDOW).isAfter(now);
    }

    /* ------------------------------------------------ current-user sending */

    @Transactional
    public WhatsAppMessageResponse send(SendMessageRequest request) {
        WhatsAppConfig config = configService.requireConnectedConfig();

        String rawPhone = resolveTargetPhone(request, config);
        String phone = phoneNumberService.normalize(rawPhone).orElseThrow(
                () -> new BadRequestException("'" + rawPhone + "' is not a valid WhatsApp phone number"));

        boolean isTemplate = request.getTemplateName() != null && !request.getTemplateName().isBlank();
        if (!isTemplate && (request.getMessage() == null || request.getMessage().isBlank())) {
            throw new BadRequestException("Either a message body or a template name is required");
        }
        if (!isTemplate && !isWindowOpen(config.getId(), phone)) {
            throw new BadRequestException(
                    "The 24-hour service window for this customer is closed — "
                    + "send an approved template instead of free text.");
        }

        boolean interactive = !isTemplate
                && request.getButtonsJson() != null && !request.getButtonsJson().isBlank();
        WhatsAppMessage message = queueOutbound(config, phone,
                isTemplate ? WhatsAppMessageType.TEMPLATE
                        : (interactive ? WhatsAppMessageType.INTERACTIVE : WhatsAppMessageType.TEXT),
                request.getMessage(), request.getTemplateName(), request.getTemplateLanguage(),
                request.getComponentsJson(), request.getRecordId(), null);

        // For INTERACTIVE the buttons ride the same side-channel templates use.
        dispatchAsync(message.getId(), interactive ? request.getButtonsJson() : request.getComponentsJson());
        return toResponse(message);
    }

    /**
     * Media send (image/video/audio/document) to a phone or conversation.
     * Free-form media follows the same 24h-window rule as text. Upload to
     * Meta happens in-request (we have the bytes here); the actual message
     * send still goes through the async dispatch pipeline.
     */
    @Transactional
    public WhatsAppMessageResponse sendMedia(String phone, Long conversationId,
                                             org.springframework.web.multipart.MultipartFile file,
                                             String caption) {
        WhatsAppConfig config = configService.requireConnectedConfig();

        String rawPhone;
        if (conversationId != null) {
            rawPhone = conversationRepository
                    .findByIdAndOwnerUserId(conversationId, config.getOwnerUserId())
                    .map(WhatsAppConversation::getCustomerPhone)
                    .orElseThrow(() -> new BadRequestException("Conversation not found"));
        } else if (phone != null && !phone.isBlank()) {
            rawPhone = phone;
        } else {
            throw new BadRequestException("Phone ya conversation chahiye");
        }
        String target = phoneNumberService.normalize(rawPhone).orElseThrow(
                () -> new BadRequestException("'" + rawPhone + "' valid WhatsApp number nahi hai"));

        if (!isWindowOpen(config.getId(), target)) {
            throw new BadRequestException(
                    "24-hour window band hai — media sirf active conversation me bheji ja sakti hai.");
        }
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File khali hai");
        }

        String mime = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        WhatsAppMessageType type = mime.startsWith("image/") ? WhatsAppMessageType.IMAGE
                : mime.startsWith("video/") ? WhatsAppMessageType.VIDEO
                : mime.startsWith("audio/") ? WhatsAppMessageType.AUDIO
                : WhatsAppMessageType.DOCUMENT;

        String mediaId;
        try {
            mediaId = ((com.xetax.crm.whatsapp.client.MetaWhatsAppSender) sender)
                    .uploadMedia(config, file.getBytes(), file.getOriginalFilename(), mime);
        } catch (com.xetax.crm.whatsapp.client.WhatsAppProviderException e) {
            throw new BadRequestException("Media upload fail: " + e.getUserMessage());
        } catch (java.io.IOException e) {
            throw new BadRequestException("File read nahi hui");
        }

        String body = (caption == null || caption.isBlank()
                ? "[" + type.name().toLowerCase() + "] " : "[" + type.name().toLowerCase() + "] " + caption)
                + (type == WhatsAppMessageType.DOCUMENT && file.getOriginalFilename() != null
                    ? " (" + file.getOriginalFilename() + ")" : "");

        WhatsAppMessage message = queueOutbound(config, target, type, body,
                null, null, null, null, null);
        message.setMediaId(mediaId);
        messageRepository.save(message);
        dispatchAsync(message.getId(), null);
        return toResponse(message);
    }

    /** Automation path — runs without a security context, scoped by form owner. */
    public void sendTextAsOwner(String ownerUserId, String rawPhone, String body) {
        WhatsAppConfig config = configRepository
                .findFirstByOwnerUserIdOrderByIdDesc(ownerUserId)
                .filter(c -> c.getStatus() == WhatsAppConnectionStatus.CONNECTED)
                .orElseThrow(() -> new IllegalArgumentException(
                        "WhatsApp is not connected for the form owner."));
        String phone = phoneNumberService.normalize(rawPhone).orElseThrow(
                () -> new IllegalArgumentException("Invalid WhatsApp phone number: " + rawPhone));
        if (!isWindowOpen(config.getId(), phone)) {
            throw new IllegalArgumentException(
                    "24-hour window closed for " + phone + " — configure an approved template "
                    + "based automation for cold sends.");
        }
        WhatsAppMessage message = queueOutbound(config, phone, WhatsAppMessageType.TEXT,
                body, null, null, null, null, null);
        dispatchAsync(message.getId(), null);
    }

    /** Document/image send scoped by owner — Documents module & automations use it. */
    public void sendDocumentAsOwner(String ownerUserId, String rawPhone, byte[] bytes,
                                    String filename, String contentType, String caption) {
        WhatsAppConfig config = configRepository
                .findFirstByOwnerUserIdOrderByIdDesc(ownerUserId)
                .filter(c -> c.getStatus() == WhatsAppConnectionStatus.CONNECTED)
                .orElseThrow(() -> new IllegalArgumentException(
                        "WhatsApp is not connected for this workspace."));
        String phone = phoneNumberService.normalize(rawPhone).orElseThrow(
                () -> new IllegalArgumentException("Invalid WhatsApp phone number: " + rawPhone));
        if (!isWindowOpen(config.getId(), phone)) {
            throw new IllegalArgumentException(
                    "The 24-hour window for " + phone + " is closed — documents can only be "
                    + "sent inside an active conversation.");
        }
        String mime = contentType == null ? "application/octet-stream" : contentType;
        WhatsAppMessageType type = mime.startsWith("image/") ? WhatsAppMessageType.IMAGE
                : WhatsAppMessageType.DOCUMENT;
        String mediaId;
        try {
            mediaId = ((com.xetax.crm.whatsapp.client.MetaWhatsAppSender) sender)
                    .uploadMedia(config, bytes, filename, mime);
        } catch (com.xetax.crm.whatsapp.client.WhatsAppProviderException e) {
            throw new IllegalArgumentException("Media upload failed: " + e.getUserMessage());
        }
        String body = "[" + type.name().toLowerCase() + "] "
                + (caption == null || caption.isBlank() ? "" : caption + " ")
                + "(" + filename + ")";
        WhatsAppMessage message = queueOutbound(config, phone, type, body,
                null, null, null, null, null);
        message.setMediaId(mediaId);
        messageRepository.save(message);
        dispatchAsync(message.getId(), null);
    }

    public List<WhatsAppMessageResponse> recordHistory(String recordId) {
        String owner = configService.currentUserId();
        return messageRepository.findTop50ByRecordIdAndOwnerUserIdOrderByIdDesc(recordId, owner)
                .stream().map(WhatsAppMessagingService::toResponse).toList();
    }

    /* --------------------------------------------------------- persistence */

    @Transactional
    public WhatsAppMessage queueOutbound(WhatsAppConfig config, String phone,
                                         WhatsAppMessageType type, String body,
                                         String templateName, String templateLanguage,
                                         String componentsJson, String recordId, Long campaignId) {
        WhatsAppConversation conversation = upsertConversation(config, phone, null);

        WhatsAppMessage message = WhatsAppMessage.builder()
                .ownerUserId(config.getOwnerUserId())
                .whatsappConfigId(config.getId())
                .conversationId(conversation.getId())
                .toPhone(phone)
                .direction(MessageDirection.OUTBOUND)
                .messageType(type)
                .body(body)
                .templateName(templateName)
                .templateLanguage(templateLanguage)
                .recordId(recordId)
                .campaignId(campaignId)
                .status(WhatsAppMessageStatus.QUEUED)
                .build();
        message = messageRepository.save(message);

        conversation.setLastMessage(previewOf(type, body, templateName));
        conversation.setLastMessageAt(Instant.now());
        if (recordId != null && conversation.getRecordId() == null) {
            conversation.setRecordId(recordId);
        }
        conversationRepository.save(conversation);
        return message;
    }

    @Transactional
    public WhatsAppConversation upsertConversation(WhatsAppConfig config, String phone, String customerName) {
        Optional<WhatsAppConversation> existing =
                conversationRepository.findByWhatsappConfigIdAndCustomerPhone(config.getId(), phone);
        if (existing.isPresent()) {
            WhatsAppConversation conversation = existing.get();
            if (customerName != null && !customerName.isBlank()
                    && (conversation.getCustomerName() == null || conversation.getCustomerName().isBlank())) {
                conversation.setCustomerName(customerName);
                conversationRepository.save(conversation);
            }
            return conversation;
        }
        WhatsAppConversation conversation = WhatsAppConversation.builder()
                .ownerUserId(config.getOwnerUserId())
                .whatsappConfigId(config.getId())
                .customerPhone(phone)
                .customerName(customerName)
                .unreadCount(0)
                .status("OPEN")
                .build();
        return conversationRepository.save(conversation);
    }

    /* ------------------------------------------------------------ dispatch */

    @Async("whatsappExecutor")
    public void dispatchAsync(Long messageId, String componentsJson) {
        try {
            dispatchNow(messageId, componentsJson);
        } catch (Exception e) {
            log.error("WhatsApp dispatch failed for message {}: {}", messageId, e.getMessage());
            markFailed(messageId, "DISPATCH", "Internal error while sending");
        }
    }

    /** Synchronous worker body — also called by the Kafka campaign consumer. */
    public void dispatchNow(Long messageId, String componentsJson) {
        WhatsAppMessage message = messageRepository.findById(messageId).orElse(null);
        if (message == null || message.getStatus() != WhatsAppMessageStatus.QUEUED) {
            return; // already handled — idempotent under retries
        }
        WhatsAppConfig config = configRepository.findById(message.getWhatsappConfigId()).orElse(null);
        if (config == null || config.getStatus() != WhatsAppConnectionStatus.CONNECTED) {
            markFailed(messageId, "NO_CONFIG", "WhatsApp is not connected");
            return;
        }

        waitForRateSlot(config.getOwnerUserId());

        boolean isMedia = message.getMediaId() != null
                && message.getMessageType() != WhatsAppMessageType.TEXT
                && message.getMessageType() != WhatsAppMessageType.TEMPLATE;
        WhatsAppSendResult result;
        if (message.getMessageType() == WhatsAppMessageType.TEMPLATE) {
            result = sender.sendTemplate(config, message.getToPhone(), message.getTemplateName(),
                    message.getTemplateLanguage(), componentsJson);
        } else if (isMedia) {
            String caption = message.getBody() == null ? null
                    : message.getBody().replaceFirst("^\\[[a-z]+\\]\\s*", "");
            result = sender.sendMedia(config, message.getToPhone(),
                    message.getMessageType().name().toLowerCase(),
                    message.getMediaId(), caption, null);
        } else if (message.getMessageType() == WhatsAppMessageType.INTERACTIVE) {
            result = sender.sendInteractive(config, message.getToPhone(), message.getBody(),
                    parseButtonLabels(componentsJson));
        } else {
            result = sender.sendText(config, message.getToPhone(), message.getBody());
        }

        applySendResult(messageId, result);
    }

    private java.util.List<String> parseButtonLabels(String buttonsJson) {
        if (buttonsJson == null || buttonsJson.isBlank()) return java.util.List.of();
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(buttonsJson);
            java.util.List<String> labels = new java.util.ArrayList<>();
            if (node.isArray()) node.forEach(b -> labels.add(b.asText()));
            return labels;
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    /** Blocks the worker (never an HTTP thread) until a send slot is free. */
    public void waitForRateSlot(String ownerUserId) {
        int limit = Math.max(1, properties.getSendsPerMinute());
        for (int i = 0; i < 180; i++) {
            if (rateLimiter.allow("wa:" + ownerUserId, limit, Duration.ofMinutes(1))) {
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Transactional
    public void applySendResult(Long messageId, WhatsAppSendResult result) {
        WhatsAppMessage message = messageRepository.findById(messageId).orElse(null);
        if (message == null) return;
        if (result.success()) {
            message.setStatus(WhatsAppMessageStatus.SENT);
            message.setProviderMessageId(result.providerMessageId());
            message.setSentAt(Instant.now());
            meterRegistry.counter("whatsapp.messages.sent").increment();
        } else {
            meterRegistry.counter("whatsapp.messages.failed").increment();
            message.setStatus(WhatsAppMessageStatus.FAILED);
            message.setErrorCode(result.errorCode());
            message.setErrorMessage(result.errorMessage());
        }
        messageRepository.save(message);
    }

    public WhatsAppMessage reload(Long messageId) {
        return messageRepository.findById(messageId).orElse(null);
    }

    @Transactional
    public void markFailed(Long messageId, String code, String errorMessage) {
        messageRepository.findById(messageId).ifPresent(message -> {
            message.setStatus(WhatsAppMessageStatus.FAILED);
            message.setErrorCode(code);
            message.setErrorMessage(errorMessage);
            messageRepository.save(message);
        });
    }

    /* ------------------------------------------------------------- helpers */

    private String resolveTargetPhone(SendMessageRequest request, WhatsAppConfig config) {
        if (request.getConversationId() != null) {
            return conversationRepository
                    .findByIdAndOwnerUserId(request.getConversationId(), config.getOwnerUserId())
                    .map(WhatsAppConversation::getCustomerPhone)
                    .orElseThrow(() -> new BadRequestException("Conversation not found"));
        }
        if (request.getRecordId() != null && !request.getRecordId().isBlank()) {
            if (request.getPhoneFieldKey() == null || request.getPhoneFieldKey().isBlank()) {
                throw new BadRequestException("phoneFieldKey is required when sending to a record");
            }
            // recordService.getById enforces form ownership for the current user
            RecordResponse record = recordService.getById(request.getRecordId());
            Object value = record.getData() == null ? null : record.getData().get(request.getPhoneFieldKey());
            if (value == null || value.toString().isBlank()) {
                throw new BadRequestException(
                        "The record has no value in field '" + request.getPhoneFieldKey() + "'");
            }
            return value.toString();
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            return request.getPhone();
        }
        throw new BadRequestException("A phone number, conversation or record target is required");
    }

    private String previewOf(WhatsAppMessageType type, String body, String templateName) {
        String preview = type == WhatsAppMessageType.TEMPLATE
                ? "[template] " + templateName
                : body == null ? "" : body;
        return preview.length() > 500 ? preview.substring(0, 500) : preview;
    }

    public static WhatsAppMessageResponse toResponse(WhatsAppMessage message) {
        return WhatsAppMessageResponse.builder()
                .id(message.getId())
                .conversationId(message.getConversationId())
                .direction(message.getDirection().name())
                .messageType(message.getMessageType().name())
                .body(message.getBody())
                .templateName(message.getTemplateName())
                .toPhone(message.getToPhone())
                .status(message.getStatus().name())
                .errorMessage(message.getErrorMessage())
                .recordId(message.getRecordId())
                .campaignId(message.getCampaignId())
                .createdAt(message.getCreatedAt())
                .sentAt(message.getSentAt())
                .deliveredAt(message.getDeliveredAt())
                .readAt(message.getReadAt())
                .build();
    }
}
