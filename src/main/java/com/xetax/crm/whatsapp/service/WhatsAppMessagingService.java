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
import com.xetax.crm.whatsapp.entity.WhatsAppTemplate;
import com.xetax.crm.whatsapp.repository.WhatsAppTemplateRepository;
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
    private final WhatsAppTemplateRepository templateRepository;
    private final WhatsAppTemplateVariables templateVariables;
    private final WhatsAppMediaService mediaService;
    private final TemplateFlowTokens flowTokens;
    /**
     * This same service, but through the Spring proxy. Calling dispatchAsync on
     * {@code this} bypassed the proxy, so @Async never applied: Meta was called
     * on the HTTP thread, inside the caller's transaction. A database error
     * while dispatching then failed the caller's request — a constraint
     * violation surfaced as a 409 — and rolled the queued message back even
     * though WhatsApp had already delivered it.
     */
    private final org.springframework.beans.factory.ObjectProvider<WhatsAppMessagingService> self;

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
                                    MeterRegistry meterRegistry,
                                    WhatsAppTemplateRepository templateRepository,
                                    WhatsAppTemplateVariables templateVariables,
                                    WhatsAppMediaService mediaService,
                                    TemplateFlowTokens flowTokens,
                                    org.springframework.beans.factory.ObjectProvider<
                                            WhatsAppMessagingService> self) {
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
        this.templateRepository = templateRepository;
        this.templateVariables = templateVariables;
        this.mediaService = mediaService;
        this.flowTokens = flowTokens;
        this.self = self;
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

        // A template is filled from the synced definition, so every variable —
        // header, body, buttons, carousel cards — gets exactly one value, or
        // the send is refused here with a sentence naming what is missing.
        String componentsJson = request.getComponentsJson();
        if (isTemplate && (componentsJson == null || componentsJson.isBlank())) {
            String language = request.getTemplateLanguage() == null || request.getTemplateLanguage().isBlank()
                    ? "en" : request.getTemplateLanguage();
            WhatsAppTemplate template = templateRepository
                    .findByWhatsappConfigIdAndNameAndLanguage(config.getId(), request.getTemplateName(), language)
                    .orElseThrow(() -> new BadRequestException("Template '" + request.getTemplateName()
                            + "' (" + language + ") is not synced — sync templates on the WhatsApp page first"));
            componentsJson = templateVariables.build(template, request.getTemplateVariables());
        }

        WhatsAppMessage message = queueOutbound(config, phone,
                isTemplate ? WhatsAppMessageType.TEMPLATE
                        : (interactive ? WhatsAppMessageType.INTERACTIVE : WhatsAppMessageType.TEXT),
                request.getMessage(), request.getTemplateName(), request.getTemplateLanguage(),
                componentsJson, request.getRecordId(), null);

        // For INTERACTIVE the buttons ride the same side-channel templates use.
        dispatchAfterCommit(message.getId(), interactive ? request.getButtonsJson() : componentsJson);
        return toResponse(message, mediaService.publicUrl(message));
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
        dispatchAfterCommit(message.getId(), null);
        return toResponse(message, mediaService.publicUrl(message));
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
        dispatchAfterCommit(message.getId(), null);
    }

    /** Connected config of a workspace, if any (no security context needed). */
    public java.util.Optional<WhatsAppConfig> connectedConfig(String ownerUserId) {
        return configRepository.findFirstByOwnerUserIdOrderByIdDesc(ownerUserId)
                .filter(c -> c.getStatus() == WhatsAppConnectionStatus.CONNECTED);
    }

    /** True when a free-text message can reach this phone right now (connected + 24h window). */
    public boolean canTextAsOwner(String ownerUserId, String rawPhone) {
        WhatsAppConfig config = connectedConfig(ownerUserId).orElse(null);
        if (config == null) return false;
        String phone = phoneNumberService.normalize(rawPhone).orElse(null);
        return phone != null && isWindowOpen(config.getId(), phone);
    }

    /**
     * Playbook / automation path for cold sends: an APPROVED template with
     * positional body parameters. Returns false when WhatsApp is not
     * connected or the template is not approved — callers fall back.
     */
    public boolean sendTemplateAsOwner(String ownerUserId, String rawPhone, String templateName,
                                       String language, java.util.List<String> bodyParams, String recordId) {
        WhatsAppConfig config = connectedConfig(ownerUserId).orElse(null);
        if (config == null || templateName == null || templateName.isBlank()) return false;
        String phone = phoneNumberService.normalize(rawPhone).orElse(null);
        if (phone == null) return false;
        String lang = language == null || language.isBlank() ? "en" : language;
        WhatsAppTemplate template = templateRepository
                .findByWhatsappConfigIdAndNameAndLanguage(config.getId(), templateName, lang).orElse(null);
        if (template == null) return false;

        // Automated senders (playbook) only know body values. Take exactly as
        // many as the body has; too few, or other variables it cannot fill,
        // means the template is skipped and the caller falls back.
        var slots = templateVariables.slotsOf(template.getComponentsJson(), template.getCategory());
        java.util.List<String> values = bodyParams == null ? java.util.List.of() : bodyParams;
        if (values.size() < slots.bodyVars()) {
            log.debug("Template {} needs {} body values, got {}", templateName, slots.bodyVars(), values.size());
            return false;
        }
        String componentsJson;
        try {
            componentsJson = templateVariables.build(template,
                    com.xetax.crm.whatsapp.dto.TemplateVariables.ofBody(values.subList(0, slots.bodyVars())));
        } catch (BadRequestException e) {
            log.debug("Template {} not sent: {}", templateName, e.getMessage());
            return false;
        }
        WhatsAppMessage message = queueOutbound(config, phone, WhatsAppMessageType.TEMPLATE,
                "[template] " + templateName, templateName, lang, componentsJson, recordId, null);
        dispatchAfterCommit(message.getId(), componentsJson);
        return true;
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
        dispatchAfterCommit(message.getId(), null);
    }

    public List<WhatsAppMessageResponse> recordHistory(String recordId) {
        String owner = configService.currentUserId();
        return messageRepository.findTop50ByRecordIdAndOwnerUserIdOrderByIdDesc(recordId, owner)
                .stream().map(m -> toResponse(m, mediaService.publicUrl(m))).toList();
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

    /**
     * Hands the message to the executor once the caller's transaction has
     * committed — through the proxy, so @Async actually applies.
     *
     * <p>Two things were wrong with calling dispatchAsync directly. It ran on
     * the caller's thread, so the HTTP request waited for Meta (and for a rate
     * limit slot, up to three minutes) and any database error while dispatching
     * came back to the user as the send's own failure. And it ran inside the
     * caller's transaction, so the QUEUED row was not committed yet: the work
     * the dispatcher did could be rolled back together with it.
     *
     * <p>Waiting for the commit is what makes the executor safe: the worker
     * looks the message up by id, which only exists for it once committed.
     * With no transaction around the call (schedulers, tests) it is dispatched
     * straight away.
     */
    private void dispatchAfterCommit(Long messageId, String componentsJson) {
        if (messageId == null) return;
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            self.getObject().dispatchAsync(messageId, componentsJson);
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        self.getObject().dispatchAsync(messageId, componentsJson);
                    }
                });
    }

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
            String withMedia = withHeaderMedia(config, message.getTemplateName(),
                    message.getTemplateLanguage(), componentsJson);
            // Flow buttons need a token of their own, or the answers cannot be traced.
            TemplateFlowTokens.Attached flows = flowTokens.attach(
                    findTemplate(config, message.getTemplateName(), message.getTemplateLanguage()),
                    message, withMedia);
            result = sender.sendTemplate(config, message.getToPhone(), message.getTemplateName(),
                    message.getTemplateLanguage(), flows.componentsJson());
            if (!result.success()) flowTokens.markUndelivered(flows.pendingIds(), result.errorMessage());
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
            // Meta hands back the same id when it recognises a retry as the
            // message it already took — our own client retries a timeout, so
            // this really happens. The id is unique here, and letting the
            // duplicate through failed the whole dispatch: the customer had
            // the message and the CRM called it FAILED (a 409 on the send,
            // before dispatch moved off the request thread). The id stays on
            // the row that first carried it; this one is simply sent.
            message.setProviderMessageId(unusedProviderId(result.providerMessageId(), message.getId()));
            message.setSentAt(Instant.now());
            meterRegistry.counter("whatsapp.messages.sent").increment();
        } else {
            meterRegistry.counter("whatsapp.messages.failed").increment();
            message.setStatus(WhatsAppMessageStatus.FAILED);
            message.setErrorCode(clamp(result.errorCode(), 32));
            message.setErrorMessage(clamp(result.errorMessage(), 500));
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
            message.setErrorCode(clamp(code, 32));
            message.setErrorMessage(clamp(errorMessage, 500));
            messageRepository.save(message);
        });
    }

    /**
     * The provider id to store, or null when another message already carries
     * it. Status webhooks then keep matching the row that got there first,
     * which is the same message as far as the customer is concerned.
     */
    private String unusedProviderId(String providerMessageId, Long messageId) {
        if (providerMessageId == null || providerMessageId.isBlank()) return providerMessageId;
        WhatsAppMessage existing = messageRepository.findByProviderMessageId(providerMessageId).orElse(null);
        if (existing == null || existing.getId().equals(messageId)) return providerMessageId;
        log.info("Meta returned provider id {} again (already on message {}) — keeping it there "
                + "and marking message {} sent", providerMessageId, existing.getId(), messageId);
        return null;
    }

    /**
     * Meta writes its own sentences, and some of them are longer than the
     * columns that hold them. MySQL rejects an over-long value rather than
     * cutting it, which used to turn "the send failed" into a failed database
     * write — so the reason is trimmed to what the column takes.
     */
    private static String clamp(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * A template with a media header needs the actual image on every send —
     * the handle used at approval time was only a sample for the reviewer.
     * The file lives on the template, so it is attached here rather than in
     * every caller: the record page, a campaign and the AI tools all send
     * templates without knowing whether one has a picture on top.
     *
     * <p>If the template has no media header, or no file was stored, the
     * caller's own components are passed through untouched.
     */
    private WhatsAppTemplate findTemplate(WhatsAppConfig config, String templateName, String language) {
        if (templateName == null || templateName.isBlank()) return null;
        return templateRepository.findByWhatsappConfigIdAndNameAndLanguage(config.getId(), templateName,
                language == null || language.isBlank() ? "en" : language).orElse(null);
    }

    private String withHeaderMedia(WhatsAppConfig config, String templateName,
                                   String language, String componentsJson) {
        if (templateName == null || templateName.isBlank()) return componentsJson;
        WhatsAppTemplate template = templateRepository
                .findByWhatsappConfigIdAndNameAndLanguage(config.getId(), templateName,
                        language == null || language.isBlank() ? "en" : language)
                .orElse(null);
        if (template == null) return componentsJson;

        String format = template.getHeaderFormat();
        String mediaUrl = template.getHeaderMediaUrl();
        boolean hasMediaHeader = format != null && mediaUrl != null && !mediaUrl.isBlank()
                && java.util.List.of("IMAGE", "VIDEO", "DOCUMENT").contains(format);
        boolean hasCards = template.getCardMediaJson() != null
                && !template.getCardMediaJson().isBlank();
        // A carousel usually has no header of its own, so the two are
        // independent — either alone is reason enough to rewrite the payload.
        if (!hasMediaHeader && !hasCards) return componentsJson;

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.List<java.util.Map<String, Object>> components = componentsJson == null
                    || componentsJson.isBlank()
                    ? new java.util.ArrayList<>()
                    : new java.util.ArrayList<>(mapper.readValue(componentsJson,
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    java.util.List<java.util.Map<String, Object>>>() {}));

            // A caller that already supplied one of these wins — it may be
            // sending a different picture to each recipient.
            boolean callerSentHeader = components.stream()
                    .anyMatch(c -> "header".equalsIgnoreCase(String.valueOf(c.get("type"))));
            boolean callerSentCarousel = components.stream()
                    .anyMatch(c -> "carousel".equalsIgnoreCase(String.valueOf(c.get("type"))));

            boolean changed = false;
            if (hasMediaHeader && !callerSentHeader) {
                String kind = format.toLowerCase();
                components.add(0, java.util.Map.of("type", "header", "parameters",
                        java.util.List.of(java.util.Map.of(
                                "type", kind, kind, java.util.Map.of("link", mediaUrl)))));
                changed = true;
            }
            if (hasCards && !callerSentCarousel) {
                java.util.Map<String, Object> carousel = carouselParameters(template, mapper);
                if (carousel != null) {
                    components.add(carousel);
                    changed = true;
                }
            }
            return changed ? mapper.writeValueAsString(components) : componentsJson;
        } catch (Exception e) {
            log.warn("Could not attach header media for template {}: {}", templateName, e.getMessage());
            return componentsJson;
        }
    }

    /**
     * Carousel cards carry their own picture on every send, exactly as the
     * header does. Card bodies with variables are not filled here — a caller
     * that needs per-card text supplies its own carousel component and this
     * step is skipped.
     */
    private java.util.Map<String, Object> carouselParameters(
            WhatsAppTemplate template, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        String cardsJson = template.getCardMediaJson();
        if (cardsJson == null || cardsJson.isBlank()) return null;
        try {
            java.util.List<String> links = mapper.readValue(cardsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
            java.util.List<java.util.Map<String, Object>> cards = new java.util.ArrayList<>();
            for (int i = 0; i < links.size(); i++) {
                String link = links.get(i);
                if (link == null || link.isBlank()) continue;
                cards.add(java.util.Map.of(
                        "card_index", i,
                        "components", java.util.List.of(java.util.Map.of(
                                "type", "header",
                                "parameters", java.util.List.of(java.util.Map.of(
                                        "type", "image", "image", java.util.Map.of("link", link)))))));
            }
            return cards.isEmpty() ? null : java.util.Map.of("type", "carousel", "cards", cards);
        } catch (Exception e) {
            log.warn("Could not build carousel parameters for {}: {}",
                    template.getName(), e.getMessage());
            return null;
        }
    }

    /* --------------------------------------------------------------- flows */

    /** A Flow is sent to a phone or to an open conversation, like any message. */
    public String resolvePhoneForFlow(String rawPhone, Long conversationId) {
        WhatsAppConfig config = configService.requireConnectedConfig();
        SendMessageRequest request = new SendMessageRequest();
        request.setPhone(rawPhone);
        request.setConversationId(conversationId);
        String resolved = resolveTargetPhone(request, config);
        return phoneNumberService.normalize(resolved).orElseThrow(
                () -> new BadRequestException("'" + resolved + "' is not a valid WhatsApp phone number"));
    }

    /**
     * Sends a published Flow.
     *
     * <p>A Flow is a free-form interactive message, so the 24-hour window
     * applies exactly as it does to text — outside it, the Flow has to travel
     * as a template with a Flow button instead.
     */
    @Transactional
    public WhatsAppMessageResponse sendFlow(com.xetax.crm.whatsapp.entity.WhatsAppFlow flow,
                                            String phone, String flowToken,
                                            com.xetax.crm.whatsapp.dto.FlowSendRequest request) {
        WhatsAppConfig config = configService.requireConnectedConfig();
        if (!isWindowOpen(config.getId(), phone)) {
            throw new BadRequestException(
                    "The 24-hour window for this customer is closed — send a template with a Flow "
                    + "button instead of the Flow on its own.");
        }
        String body = request.getBodyText() == null || request.getBodyText().isBlank()
                ? "Please fill this in" : request.getBodyText();

        WhatsAppMessage message = queueOutbound(config, phone, WhatsAppMessageType.INTERACTIVE,
                body, null, null, null, request.getRecordId(), null);

        WhatsAppSendResult result = sender.sendFlow(config, phone, flow.getMetaFlowId(),
                flowToken, request.getCtaText(), body,
                request.getHeaderText(), request.getFooterText());
        applySendResult(message.getId(), result);
        WhatsAppMessage saved = reload(message.getId());
        return toResponse(saved, mediaService.publicUrl(saved));
    }

    /* ------------------------------------------------------------- helpers */

    private String resolveTargetPhone(SendMessageRequest request, WhatsAppConfig config) {
        if (request.getConversationId() != null) {
            return conversationRepository
                    .findByIdAndOwnerUserId(request.getConversationId(), config.getOwnerUserId())
                    .map(WhatsAppConversation::getCustomerPhone)
                    .orElseThrow(() -> new BadRequestException("Conversation not found"));
        }
        // An explicit number wins over the record. recordId has two jobs: it
        // can name the target, but callers that already know the number (the
        // record page's chat panel) send it only to file the message against
        // that record. Reading it as a target there rejected a send that had a
        // perfectly good phone number, asking for a phoneFieldKey nobody needs.
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            return request.getPhone();
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
        throw new BadRequestException("A phone number, conversation or record target is required");
    }

    private String previewOf(WhatsAppMessageType type, String body, String templateName) {
        String preview = type == WhatsAppMessageType.TEMPLATE
                ? "[template] " + templateName
                : body == null ? "" : body;
        return preview.length() > 500 ? preview.substring(0, 500) : preview;
    }

    /**
     * The wire shape of one message. mediaUrl is passed in rather than looked
     * up here because it is built from configuration (the public API host),
     * which a static mapper cannot read — see WhatsAppMediaService#publicUrl.
     */
    public static WhatsAppMessageResponse toResponse(WhatsAppMessage message, String mediaUrl) {
        return WhatsAppMessageResponse.builder()
                .id(message.getId())
                .conversationId(message.getConversationId())
                .direction(message.getDirection().name())
                .messageType(message.getMessageType().name())
                .body(message.getBody())
                .mediaUrl(mediaUrl)
                .mediaMimeType(message.getMediaMimeType())
                .mediaFilename(message.getMediaFilename())
                .mediaSize(message.getMediaSize())
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
