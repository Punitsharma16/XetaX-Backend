package com.xetax.crm.whatsapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.common.util.CsvParser;
import com.xetax.crm.common.util.PlaceholderResolver;
import com.xetax.crm.data_manager.dto.RecordResponse;
import com.xetax.crm.data_manager.dto.SeacrhDto.RecordSearchRequest;
import com.xetax.crm.data_manager.service.RecordService;
import com.xetax.crm.whatsapp.dto.CampaignCreateRequest;
import com.xetax.crm.whatsapp.dto.CampaignRecipientResponse;
import com.xetax.crm.whatsapp.dto.CampaignResponse;
import com.xetax.crm.whatsapp.entity.*;
import com.xetax.crm.whatsapp.enums.*;
import com.xetax.crm.whatsapp.kafka.WhatsAppEventPublisher;
import com.xetax.crm.whatsapp.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.*;

/**
 * Bulk WhatsApp campaigns. Audience comes from CRM records (owner-scoped
 * through RecordService) or an uploaded CSV. Starting a campaign queues one
 * Kafka message per recipient on xetax.whatsapp.campaign.send; the consumer
 * sends under the per-owner rate limit. If the broker is down the whole
 * batch falls back to the whatsappExecutor so campaigns still run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppCampaignService {

    private static final int MAX_RECIPIENTS = 5000;

    private final WhatsAppCampaignRepository campaignRepository;
    private final WhatsAppCampaignRecipientRepository recipientRepository;
    private final WhatsAppConfigRepository configRepository;
    private final WhatsAppConfigService configService;
    private final WhatsAppMessagingService messagingService;
    private final PhoneNumberService phoneNumberService;
    private final RecordService recordService;
    private final WhatsAppEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final KnowledgeIndexer knowledgeIndexer;

    /* ------------------------------------------------------------- create */

    @Transactional
    public CampaignResponse create(CampaignCreateRequest request) {
        WhatsAppConfig config = configService.requireConnectedConfig();

        if (request.getName() == null || request.getName().isBlank()) {
            throw new BadRequestException("Campaign name is required");
        }
        boolean hasTemplate = request.getTemplateName() != null && !request.getTemplateName().isBlank();
        boolean hasText = request.getMessageTemplate() != null && !request.getMessageTemplate().isBlank();
        if (!hasTemplate && !hasText) {
            throw new BadRequestException("A message text or an approved template is required");
        }

        CampaignSourceType sourceType;
        try {
            sourceType = CampaignSourceType.valueOf(
                    request.getSourceType() == null ? "" : request.getSourceType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("sourceType must be RECORDS or CSV");
        }

        WhatsAppCampaign campaign = WhatsAppCampaign.builder()
                .ownerUserId(config.getOwnerUserId())
                .whatsappConfigId(config.getId())
                .name(request.getName().trim())
                .messageTemplate(request.getMessageTemplate())
                .templateName(hasTemplate ? request.getTemplateName() : null)
                .templateLanguage(request.getTemplateLanguage())
                .sourceType(sourceType)
                .templateParamsJson(writeParams(request.getTemplateParams()))
                .status(CampaignStatus.DRAFT)
                .build();
        campaign = campaignRepository.save(campaign);

        if (sourceType == CampaignSourceType.RECORDS) {
            loadRecipientsFromRecords(campaign, request);
        } else {
            campaign.setTargetDescription("CSV upload pending");
        }
        campaign = campaignRepository.save(campaign);
        indexCampaign(campaign);
        return toResponse(campaign);
    }

    /** Pulls the audience through RecordService — ownership enforced there. */
    private void loadRecipientsFromRecords(WhatsAppCampaign campaign, CampaignCreateRequest request) {
        if (request.getFormSlug() == null || request.getFormSlug().isBlank()
                || request.getPhoneFieldKey() == null || request.getPhoneFieldKey().isBlank()) {
            throw new BadRequestException("formSlug and phoneFieldKey are required for a RECORDS campaign");
        }

        Set<String> seenPhones = new HashSet<>();
        int added = 0, skipped = 0, page = 0;
        while (added < MAX_RECIPIENTS) {
            RecordSearchRequest search = new RecordSearchRequest();
            search.setPage(page++);
            search.setSize(200);
            search.setSearch(request.getSearch());
            if (request.getFilters() != null) search.setFilters(request.getFilters());
            Page<RecordResponse> batch = recordService.search(request.getFormSlug(), search);
            if (batch.isEmpty()) break;

            for (RecordResponse record : batch.getContent()) {
                Object raw = record.getData() == null ? null
                        : record.getData().get(request.getPhoneFieldKey());
                Optional<String> phone = phoneNumberService.normalize(raw == null ? null : raw.toString());
                if (phone.isEmpty() || !seenPhones.add(phone.get())) {
                    skipped++;
                    continue;
                }
                recipientRepository.save(WhatsAppCampaignRecipient.builder()
                        .campaignId(campaign.getId())
                        .phone(phone.get())
                        .recordId(record.getId())
                        .payloadJson(writeJson(record.getData()))
                        .status(RecipientStatus.PENDING)
                        .build());
                if (++added >= MAX_RECIPIENTS) break;
            }
            if (!batch.hasNext()) break;
        }

        campaign.setTotalCount(added);
        campaign.setTargetDescription("Records of form '" + request.getFormSlug() + "'"
                + (skipped > 0 ? " (" + skipped + " rows skipped: bad/duplicate phone)" : ""));
        if (added == 0) {
            throw new BadRequestException(
                    "No records with a usable phone number matched — check the phone field and filters");
        }
    }

    /* --------------------------------------------------------- CSV upload */

    @Transactional
    public CampaignResponse uploadCsv(Long campaignId, MultipartFile file) {
        WhatsAppCampaign campaign = requireMyCampaign(campaignId);
        if (campaign.getSourceType() != CampaignSourceType.CSV) {
            throw new BadRequestException("This campaign does not take a CSV audience");
        }
        if (campaign.getStatus() != CampaignStatus.DRAFT) {
            throw new BadRequestException("Recipients can only be added before the campaign starts");
        }

        List<List<String>> rows;
        try {
            rows = CsvParser.parse(file.getInputStream());
        } catch (Exception e) {
            throw new BadRequestException("Could not read the CSV file");
        }
        if (rows.size() < 2) {
            throw new BadRequestException("The CSV needs a header row and at least one data row");
        }

        List<String> header = rows.get(0).stream().map(h -> h == null ? "" : h.trim()).toList();
        int phoneCol = -1;
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).toLowerCase();
            if (name.equals("phone") || name.equals("mobile") || name.contains("phone")) {
                phoneCol = i;
                break;
            }
        }
        if (phoneCol < 0) {
            throw new BadRequestException("The CSV must have a 'phone' column");
        }

        Set<String> seenPhones = new HashSet<>();
        int added = 0, skipped = 0;
        for (int r = 1; r < rows.size() && added < MAX_RECIPIENTS; r++) {
            List<String> row = rows.get(r);
            String rawPhone = phoneCol < row.size() ? row.get(phoneCol) : null;
            Optional<String> phone = phoneNumberService.normalize(rawPhone);
            if (phone.isEmpty() || !seenPhones.add(phone.get())) {
                skipped++;
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            for (int c = 0; c < header.size() && c < row.size(); c++) {
                if (!header.get(c).isBlank()) payload.put(header.get(c), row.get(c));
            }
            recipientRepository.save(WhatsAppCampaignRecipient.builder()
                    .campaignId(campaign.getId())
                    .phone(phone.get())
                    .payloadJson(writeJson(payload))
                    .status(RecipientStatus.PENDING)
                    .build());
            added++;
        }
        if (added == 0) {
            throw new BadRequestException("No rows had a valid phone number");
        }

        campaign.setTotalCount(campaign.getTotalCount() + added);
        campaign.setTargetDescription("CSV '" + file.getOriginalFilename() + "' — " + added + " recipients"
                + (skipped > 0 ? " (" + skipped + " skipped: bad/duplicate phone)" : ""));
        campaign = campaignRepository.save(campaign);
        return toResponse(campaign);
    }

    /* -------------------------------------------------------------- start */

    @Transactional
    public CampaignResponse start(Long campaignId, Instant scheduledAt) {
        WhatsAppCampaign campaign = requireMyCampaign(campaignId);
        if (campaign.getStatus() != CampaignStatus.DRAFT
                && campaign.getStatus() != CampaignStatus.SCHEDULED) {
            throw new BadRequestException("Only a draft or scheduled campaign can be started");
        }
        if (scheduledAt != null && scheduledAt.isAfter(Instant.now())) {
            campaign.setScheduledAt(scheduledAt);
            campaign.setStatus(CampaignStatus.SCHEDULED);
            return toResponse(campaignRepository.save(campaign));
        }
        return startNow(campaign);
    }

    /** Fires SCHEDULED campaigns whose time has come. System-level, all owners. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
    @Transactional
    public void startDueScheduledCampaigns() {
        for (WhatsAppCampaign campaign : campaignRepository
                .findByStatusAndScheduledAtLessThanEqual(CampaignStatus.SCHEDULED, Instant.now())) {
            try {
                startNow(campaign);
                log.info("Scheduled campaign {} started", campaign.getId());
            } catch (Exception e) {
                log.error("Scheduled campaign {} failed to start: {}", campaign.getId(), e.getMessage());
            }
        }
    }

    private CampaignResponse startNow(WhatsAppCampaign campaign) {
        Long campaignId = campaign.getId();
        List<WhatsAppCampaignRecipient> pending =
                recipientRepository.findByCampaignIdAndStatus(campaignId, RecipientStatus.PENDING);
        if (pending.isEmpty()) {
            throw new BadRequestException("The campaign has no recipients yet");
        }

        campaign.setScheduledAt(null);
        campaign.setStatus(CampaignStatus.RUNNING);
        campaign.setStartedAt(Instant.now());
        campaign.setQueuedCount(pending.size());
        campaign = campaignRepository.save(campaign);

        List<Long> fallback = new ArrayList<>();
        for (WhatsAppCampaignRecipient recipient : pending) {
            recipient.setStatus(RecipientStatus.QUEUED);
            recipientRepository.save(recipient);
            String payload = "{\"recipientId\":" + recipient.getId() + "}";
            if (!eventPublisher.publishCampaignSend(campaignId, payload)) {
                fallback.add(recipient.getId());
            }
        }
        if (!fallback.isEmpty()) {
            log.warn("Kafka unavailable — {} campaign sends running on whatsappExecutor instead",
                    fallback.size());
            processRecipientsAsync(fallback);
        }
        indexCampaign(campaign);
        return toResponse(campaign);
    }

    @Transactional
    public CampaignResponse cancel(Long campaignId) {
        WhatsAppCampaign campaign = requireMyCampaign(campaignId);
        if (campaign.getStatus() == CampaignStatus.COMPLETED
                || campaign.getStatus() == CampaignStatus.CANCELLED) {
            return toResponse(campaign);
        }
        campaign.setStatus(CampaignStatus.CANCELLED);
        campaign.setCompletedAt(Instant.now());
        return toResponse(campaignRepository.save(campaign));
    }

    @Transactional
    public CampaignResponse pause(Long campaignId) {
        WhatsAppCampaign campaign = requireMyCampaign(campaignId);
        if (campaign.getStatus() != CampaignStatus.RUNNING) {
            throw new BadRequestException("Only a running campaign can be paused");
        }
        campaign.setStatus(CampaignStatus.PAUSED);
        return toResponse(campaignRepository.save(campaign));
    }

    /** Re-queues everything still QUEUED — idempotent workers make this safe. */
    @Transactional
    public CampaignResponse resume(Long campaignId) {
        WhatsAppCampaign campaign = requireMyCampaign(campaignId);
        if (campaign.getStatus() != CampaignStatus.PAUSED) {
            throw new BadRequestException("Only a paused campaign can be resumed");
        }
        campaign.setStatus(CampaignStatus.RUNNING);
        campaign = campaignRepository.save(campaign);

        List<Long> fallback = new ArrayList<>();
        for (WhatsAppCampaignRecipient recipient :
                recipientRepository.findByCampaignIdAndStatus(campaignId, RecipientStatus.QUEUED)) {
            String payload = "{\"recipientId\":" + recipient.getId() + "}";
            if (!eventPublisher.publishCampaignSend(campaignId, payload)) {
                fallback.add(recipient.getId());
            }
        }
        if (!fallback.isEmpty()) {
            processRecipientsAsync(fallback);
        }
        return toResponse(campaign);
    }

    /* ----------------------------------------------------------- workers */

    @Async("whatsappExecutor")
    public void processRecipientsAsync(List<Long> recipientIds) {
        recipientIds.forEach(this::processRecipient);
    }

    /**
     * Sends one recipient. Idempotent: only a QUEUED recipient is processed,
     * so Kafka redeliveries and the inline fallback can overlap safely.
     */
    public void processRecipient(Long recipientId) {
        WhatsAppCampaignRecipient recipient = recipientRepository.findById(recipientId).orElse(null);
        if (recipient == null || recipient.getStatus() != RecipientStatus.QUEUED) {
            return;
        }
        WhatsAppCampaign campaign = campaignRepository.findById(recipient.getCampaignId()).orElse(null);
        if (campaign == null) return;

        if (campaign.getStatus() == CampaignStatus.CANCELLED) {
            failRecipient(recipient, campaign, "Campaign cancelled");
            return;
        }
        if (campaign.getStatus() == CampaignStatus.PAUSED
                || campaign.getStatus() == CampaignStatus.SCHEDULED) {
            return; // stays QUEUED — resume() re-publishes it
        }
        WhatsAppConfig config = configRepository.findById(campaign.getWhatsappConfigId())
                .filter(c -> c.getStatus() == WhatsAppConnectionStatus.CONNECTED)
                .orElse(null);
        if (config == null) {
            failRecipient(recipient, campaign, "WhatsApp is not connected");
            return;
        }

        try {
            Map<String, Object> payload = readJson(recipient.getPayloadJson());
            boolean isTemplate = campaign.getTemplateName() != null && !campaign.getTemplateName().isBlank();

            // Free-text campaigns obey the 24h service window per recipient.
            if (!isTemplate && !messagingService.isWindowOpen(config.getId(), recipient.getPhone())) {
                failRecipient(recipient, campaign,
                        "24-hour window closed — approved template required");
                return;
            }

            String body = isTemplate ? null
                    : PlaceholderResolver.resolve(campaign.getMessageTemplate(), payload);
            String componentsJson = isTemplate
                    ? buildTemplateComponents(campaign.getTemplateParamsJson(), payload)
                    : null;

            WhatsAppMessage message = messagingService.queueOutbound(config, recipient.getPhone(),
                    isTemplate ? WhatsAppMessageType.TEMPLATE : WhatsAppMessageType.TEXT,
                    body, campaign.getTemplateName(), campaign.getTemplateLanguage(),
                    componentsJson, recipient.getRecordId(), campaign.getId());

            recipient.setAttemptCount(recipient.getAttemptCount() + 1);
            recipient.setLastAttemptAt(Instant.now());
            recipientRepository.save(recipient);

            messagingService.dispatchNow(message.getId(), componentsJson);

            WhatsAppMessage sent = messagingService.reload(message.getId());
            if (sent != null && sent.getStatus() == WhatsAppMessageStatus.SENT) {
                recipient.setStatus(RecipientStatus.SENT);
                recipient.setProviderMessageId(sent.getProviderMessageId());
                recipient.setError(null);
                recipientRepository.save(recipient);
                campaignRepository.markOneSent(campaign.getId());
            } else {
                recipient.setStatus(RecipientStatus.FAILED);
                recipient.setError(sent == null ? "Send failed" : sent.getErrorMessage());
                recipientRepository.save(recipient);
                campaignRepository.markOneFailed(campaign.getId());
            }
        } catch (Exception e) {
            log.error("Campaign recipient {} failed: {}", recipientId, e.getMessage());
            recipient.setStatus(RecipientStatus.FAILED);
            recipient.setError("Internal error while sending");
            recipientRepository.save(recipient);
            campaignRepository.markOneFailed(campaign.getId());
        }
        maybeComplete(campaign.getId());
    }

    private void failRecipient(WhatsAppCampaignRecipient recipient,
                               WhatsAppCampaign campaign, String reason) {
        recipient.setStatus(RecipientStatus.FAILED);
        recipient.setError(reason);
        recipientRepository.save(recipient);
        campaignRepository.markOneFailed(campaign.getId());
        maybeComplete(campaign.getId());
    }

    private void maybeComplete(Long campaignId) {
        long open = recipientRepository.countByCampaignIdAndStatusIn(campaignId,
                List.of(RecipientStatus.PENDING, RecipientStatus.QUEUED));
        if (open > 0) return;
        campaignRepository.findById(campaignId).ifPresent(campaign -> {
            if (campaign.getStatus() != CampaignStatus.RUNNING) return;
            campaign.setStatus(campaign.getFailedCount() > 0
                    ? (campaign.getSentCount() > 0 ? CampaignStatus.PARTIAL : CampaignStatus.FAILED)
                    : CampaignStatus.COMPLETED);
            campaign.setCompletedAt(Instant.now());
            campaignRepository.save(campaign);
            indexCampaign(campaign);
        });
    }

    /* -------------------------------------------------------------- reads */

    public Page<CampaignResponse> list(int page, int size) {
        return campaignRepository.findByOwnerUserIdOrderByIdDesc(configService.currentUserId(),
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)))
                .map(WhatsAppCampaignService::toResponse);
    }

    public CampaignResponse get(Long campaignId) {
        return toResponse(requireMyCampaign(campaignId));
    }

    public Page<CampaignRecipientResponse> recipients(Long campaignId, int page, int size, String status) {
        WhatsAppCampaign campaign = requireMyCampaign(campaignId);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200));
        Page<WhatsAppCampaignRecipient> result;
        if (status != null && !status.isBlank()) {
            RecipientStatus filter;
            try {
                filter = RecipientStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown recipient status: " + status);
            }
            result = recipientRepository.findByCampaignIdAndStatusOrderByIdAsc(
                    campaign.getId(), filter, pageable);
        } else {
            result = recipientRepository.findByCampaignIdOrderByIdAsc(campaign.getId(), pageable);
        }
        return result.map(r -> CampaignRecipientResponse.builder()
                .id(r.getId())
                .phone(r.getPhone())
                .recordId(r.getRecordId())
                .status(r.getStatus().name())
                .attemptCount(r.getAttemptCount())
                .error(r.getError())
                .build());
    }

    /* ------------------------------------------------------------ helpers */

    private WhatsAppCampaign requireMyCampaign(Long campaignId) {
        return campaignRepository.findByIdAndOwnerUserId(campaignId, configService.currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found"));
    }

    private String writeParams(List<String> params) {
        try {
            return params == null || params.isEmpty() ? null : objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Builds the Meta template components array: the campaign's ordered
     * payload keys become {{1}},{{2}}… body parameters, values taken from
     * this recipient's payload (record data / CSV row). Missing values send
     * as empty strings rather than failing the whole recipient.
     */
    String buildTemplateComponents(String templateParamsJson, Map<String, Object> payload) {
        try {
            if (templateParamsJson == null || templateParamsJson.isBlank()) return null;
            List<String> keys = objectMapper.readValue(templateParamsJson,
                    new TypeReference<List<String>>() {});
            if (keys.isEmpty()) return null;
            List<Map<String, Object>> parameters = new ArrayList<>();
            for (String key : keys) {
                Object value = payload.get(key);
                parameters.add(Map.of("type", "text",
                        "text", value == null ? "" : String.valueOf(value)));
            }
            return objectMapper.writeValueAsString(List.of(
                    Map.of("type", "body", "parameters", parameters)));
        } catch (Exception e) {
            log.warn("Template components build failed: {}", e.getMessage());
            return null;
        }
    }

    private String writeJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data == null ? Map.of() : data);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> readJson(String json) {
        try {
            if (json == null || json.isBlank()) return Map.of();
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Campaign metadata only — never message bodies with customer data. */
    private void indexCampaign(WhatsAppCampaign campaign) {
        try {
            String content = "WhatsApp campaign '" + campaign.getName() + "'"
                    + ". Status: " + campaign.getStatus()
                    + ". Audience: " + campaign.getTargetDescription()
                    + ". Recipients: " + campaign.getTotalCount()
                    + ", sent " + campaign.getSentCount()
                    + ", delivered " + campaign.getDeliveredCount()
                    + ", read " + campaign.getReadCount()
                    + ", failed " + campaign.getFailedCount() + ".";
            knowledgeIndexer.reindexEntity("whatsapp-campaign", campaign.getId(), content,
                    UUID.fromString(campaign.getOwnerUserId()));
        } catch (Exception e) {
            log.debug("Campaign knowledge indexing skipped: {}", e.getMessage());
        }
    }

    public static CampaignResponse toResponse(WhatsAppCampaign campaign) {
        return CampaignResponse.builder()
                .id(campaign.getId())
                .name(campaign.getName())
                .sourceType(campaign.getSourceType().name())
                .status(campaign.getStatus().name())
                .messageTemplate(campaign.getMessageTemplate())
                .templateName(campaign.getTemplateName())
                .templateLanguage(campaign.getTemplateLanguage())
                .targetDescription(campaign.getTargetDescription())
                .totalCount(campaign.getTotalCount())
                .queuedCount(campaign.getQueuedCount())
                .sentCount(campaign.getSentCount())
                .deliveredCount(campaign.getDeliveredCount())
                .readCount(campaign.getReadCount())
                .failedCount(campaign.getFailedCount())
                .createdAt(campaign.getCreatedAt())
                .scheduledAt(campaign.getScheduledAt())
                .startedAt(campaign.getStartedAt())
                .completedAt(campaign.getCompletedAt())
                .build();
    }
}
