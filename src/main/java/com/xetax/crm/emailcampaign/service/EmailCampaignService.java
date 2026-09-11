package com.xetax.crm.emailcampaign.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.common.exception.UnauthorizedException;
import com.xetax.crm.common.ratelimit.RateLimiterService;
import com.xetax.crm.common.util.CsvParser;
import com.xetax.crm.common.util.PlaceholderResolver;
import com.xetax.crm.contact.Contact;
import com.xetax.crm.contact.ContactRepository;
import com.xetax.crm.data_manager.dto.RecordResponse;
import com.xetax.crm.data_manager.dto.SeacrhDto.RecordSearchRequest;
import com.xetax.crm.data_manager.service.RecordService;
import com.xetax.crm.emailcampaign.config.EmailCampaignProperties;
import com.xetax.crm.emailcampaign.dto.EmailCampaignCreateRequest;
import com.xetax.crm.emailcampaign.dto.EmailCampaignRecipientResponse;
import com.xetax.crm.emailcampaign.dto.EmailCampaignResponse;
import com.xetax.crm.emailcampaign.entity.EmailCampaign;
import com.xetax.crm.emailcampaign.entity.EmailCampaignRecipient;
import com.xetax.crm.emailcampaign.enums.EmailCampaignSourceType;
import com.xetax.crm.emailcampaign.enums.EmailCampaignStatus;
import com.xetax.crm.emailcampaign.enums.EmailRecipientStatus;
import com.xetax.crm.emailcampaign.kafka.EmailCampaignEventPublisher;
import com.xetax.crm.emailcampaign.repository.EmailCampaignRecipientRepository;
import com.xetax.crm.emailcampaign.repository.EmailCampaignRepository;
import com.xetax.crm.emailcampaign.util.EmailAddressUtil;
import com.xetax.crm.settings.entity.OrgSmtpSettings;
import com.xetax.crm.settings.repository.OrgSmtpSettingsRepository;
import com.xetax.crm.settings.service.OrgSmtpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bulk email campaigns, sent from the org's OWN SMTP account (the one saved
 * under Profile → Email). There is deliberately no fallback to the platform
 * mailbox: a marketing blast must always leave from the customer's address.
 *
 * <p>Same shape as WhatsAppCampaignService: audience from CRM records, all
 * contacts, or a CSV; starting queues one Kafka message per recipient on
 * xetax.email.campaign.send; the consumer sends under the per-owner rate
 * limit; if the broker is down the batch runs on the emailExecutor instead.
 * Every send lands in email_logs through OrgSmtpService.sendAs, so the
 * contact page shows campaign emails in the customer's history like any
 * other email.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCampaignService {

    private final EmailCampaignRepository campaignRepository;
    private final EmailCampaignRecipientRepository recipientRepository;
    private final OrgSmtpSettingsRepository smtpSettingsRepository;
    private final OrgSmtpService orgSmtpService;
    private final ContactRepository contactRepository;
    private final RecordService recordService;
    private final CurrentUserProvider currentUserProvider;
    private final RateLimiterService rateLimiter;
    private final EmailCampaignEventPublisher eventPublisher;
    private final EmailCampaignProperties properties;
    private final ObjectMapper objectMapper;
    private final KnowledgeIndexer knowledgeIndexer;

    /* ------------------------------------------------------------- status */

    /** What the panel needs before showing the wizard: is there a sender at all? */
    public Map<String, Object> status() {
        String owner = currentUserId();
        OrgSmtpSettings smtp = smtpSettingsRepository.findByOwnerUserId(owner).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", smtp != null);
        out.put("fromEmail", smtp == null ? null : smtp.getUsername());
        out.put("maxRecipients", properties.getMaxRecipients());
        out.put("sendsPerMinute", properties.getSendsPerMinute());
        return out;
    }

    /* ------------------------------------------------------------- create */

    @Transactional
    public EmailCampaignResponse create(EmailCampaignCreateRequest request) {
        String owner = currentUserId();
        requireOwnSmtp(owner);

        if (request.getName() == null || request.getName().isBlank()) {
            throw new BadRequestException("Campaign name is required");
        }
        if (request.getSubject() == null || request.getSubject().isBlank()) {
            throw new BadRequestException("Subject is required");
        }
        if (request.getBody() == null || request.getBody().isBlank()) {
            throw new BadRequestException("Message body is required");
        }

        EmailCampaignSourceType sourceType;
        try {
            sourceType = EmailCampaignSourceType.valueOf(
                    request.getSourceType() == null ? "" : request.getSourceType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("sourceType must be RECORDS, CONTACTS or CSV");
        }

        EmailCampaign campaign = EmailCampaign.builder()
                .ownerUserId(owner)
                .name(request.getName().trim())
                .subject(request.getSubject().trim())
                .body(request.getBody())
                .sourceType(sourceType)
                .status(EmailCampaignStatus.DRAFT)
                .build();
        campaign = campaignRepository.save(campaign);

        switch (sourceType) {
            case RECORDS -> loadRecipientsFromRecords(campaign, request);
            case CONTACTS -> loadRecipientsFromContacts(campaign, owner);
            case CSV -> campaign.setTargetDescription("CSV upload pending");
        }
        campaign = campaignRepository.save(campaign);
        indexCampaign(campaign);
        return toResponse(campaign);
    }

    /** Pulls the audience through RecordService — ownership enforced there. */
    private void loadRecipientsFromRecords(EmailCampaign campaign, EmailCampaignCreateRequest request) {
        if (request.getFormSlug() == null || request.getFormSlug().isBlank()
                || request.getEmailFieldKey() == null || request.getEmailFieldKey().isBlank()) {
            throw new BadRequestException("formSlug and emailFieldKey are required for a RECORDS campaign");
        }

        int max = properties.getMaxRecipients();
        Set<String> seen = new HashSet<>();
        int added = 0, skipped = 0, page = 0;
        while (added < max) {
            RecordSearchRequest search = new RecordSearchRequest();
            search.setPage(page++);
            search.setSize(200);
            search.setSearch(request.getSearch());
            if (request.getFilters() != null) search.setFilters(request.getFilters());
            Page<RecordResponse> batch = recordService.search(request.getFormSlug(), search);
            if (batch.isEmpty()) break;

            for (RecordResponse record : batch.getContent()) {
                Object raw = record.getData() == null ? null
                        : record.getData().get(request.getEmailFieldKey());
                Optional<String> email = EmailAddressUtil.normalize(raw == null ? null : raw.toString());
                if (email.isEmpty() || !seen.add(email.get())) {
                    skipped++;
                    continue;
                }
                recipientRepository.save(EmailCampaignRecipient.builder()
                        .campaignId(campaign.getId())
                        .email(email.get())
                        .recordId(record.getId())
                        .payloadJson(writeJson(record.getData()))
                        .status(EmailRecipientStatus.PENDING)
                        .build());
                if (++added >= max) break;
            }
            if (!batch.hasNext()) break;
        }

        campaign.setTotalCount(added);
        campaign.setTargetDescription("Records of form '" + request.getFormSlug() + "'"
                + (skipped > 0 ? " (" + skipped + " rows skipped: bad/duplicate email)" : ""));
        if (added == 0) {
            throw new BadRequestException(
                    "No records with a usable email address matched — check the email field and filters");
        }
    }

    /** Every contact of the org that has an email. Placeholders: {name} {email} {phone} {company}. */
    private void loadRecipientsFromContacts(EmailCampaign campaign, String owner) {
        int max = properties.getMaxRecipients();
        Set<String> seen = new HashSet<>();
        int added = 0, skipped = 0;
        for (Contact contact : contactRepository.findAllByOwnerUserIdOrderByNameAsc(owner)) {
            Optional<String> email = EmailAddressUtil.normalize(contact.getEmail());
            if (email.isEmpty() || !seen.add(email.get())) {
                skipped++;
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", contact.getName() == null ? "" : contact.getName());
            payload.put("email", email.get());
            payload.put("phone", contact.getPhone() == null ? "" : contact.getPhone());
            payload.put("company", contact.getCompany() == null ? "" : contact.getCompany());
            recipientRepository.save(EmailCampaignRecipient.builder()
                    .campaignId(campaign.getId())
                    .email(email.get())
                    .contactId(contact.getId())
                    .payloadJson(writeJson(payload))
                    .status(EmailRecipientStatus.PENDING)
                    .build());
            if (++added >= max) break;
        }

        campaign.setTotalCount(added);
        campaign.setTargetDescription("All contacts with an email address"
                + (skipped > 0 ? " (" + skipped + " skipped: no/duplicate email)" : ""));
        if (added == 0) {
            throw new BadRequestException("None of your contacts has an email address yet");
        }
    }

    /* --------------------------------------------------------- CSV upload */

    @Transactional
    public EmailCampaignResponse uploadCsv(Long campaignId, MultipartFile file) {
        EmailCampaign campaign = requireMyCampaign(campaignId);
        if (campaign.getSourceType() != EmailCampaignSourceType.CSV) {
            throw new BadRequestException("This campaign does not take a CSV audience");
        }
        if (campaign.getStatus() != EmailCampaignStatus.DRAFT) {
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
        int emailCol = -1;
        for (int i = 0; i < header.size(); i++) {
            if (EmailAddressUtil.isEmailHeader(header.get(i))) {
                emailCol = i;
                break;
            }
        }
        if (emailCol < 0) {
            throw new BadRequestException("The CSV must have an 'email' column");
        }

        int max = properties.getMaxRecipients();
        Set<String> seen = new HashSet<>();
        int added = 0, skipped = 0;
        for (int r = 1; r < rows.size() && added < max; r++) {
            List<String> row = rows.get(r);
            String rawEmail = emailCol < row.size() ? row.get(emailCol) : null;
            Optional<String> email = EmailAddressUtil.normalize(rawEmail);
            if (email.isEmpty() || !seen.add(email.get())) {
                skipped++;
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            for (int c = 0; c < header.size() && c < row.size(); c++) {
                if (!header.get(c).isBlank()) payload.put(header.get(c), row.get(c));
            }
            recipientRepository.save(EmailCampaignRecipient.builder()
                    .campaignId(campaign.getId())
                    .email(email.get())
                    .payloadJson(writeJson(payload))
                    .status(EmailRecipientStatus.PENDING)
                    .build());
            added++;
        }
        if (added == 0) {
            throw new BadRequestException("No rows had a valid email address");
        }

        campaign.setTotalCount(campaign.getTotalCount() + added);
        campaign.setTargetDescription("CSV '" + file.getOriginalFilename() + "' — " + added + " recipients"
                + (skipped > 0 ? " (" + skipped + " skipped: bad/duplicate email)" : ""));
        campaign = campaignRepository.save(campaign);
        return toResponse(campaign);
    }

    /* -------------------------------------------------------------- start */

    @Transactional
    public EmailCampaignResponse start(Long campaignId, Instant scheduledAt) {
        EmailCampaign campaign = requireMyCampaign(campaignId);
        if (campaign.getStatus() != EmailCampaignStatus.DRAFT
                && campaign.getStatus() != EmailCampaignStatus.SCHEDULED) {
            throw new BadRequestException("Only a draft or scheduled campaign can be started");
        }
        requireOwnSmtp(campaign.getOwnerUserId());
        if (scheduledAt != null && scheduledAt.isAfter(Instant.now())) {
            campaign.setScheduledAt(scheduledAt);
            campaign.setStatus(EmailCampaignStatus.SCHEDULED);
            return toResponse(campaignRepository.save(campaign));
        }
        return startNow(campaign);
    }

    /** Fires SCHEDULED campaigns whose time has come. System-level, all owners. */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void startDueScheduledCampaigns() {
        for (EmailCampaign campaign : campaignRepository
                .findByStatusAndScheduledAtLessThanEqual(EmailCampaignStatus.SCHEDULED, Instant.now())) {
            try {
                startNow(campaign);
                log.info("Scheduled email campaign {} started", campaign.getId());
            } catch (Exception e) {
                log.error("Scheduled email campaign {} failed to start: {}", campaign.getId(), e.getMessage());
            }
        }
    }

    private EmailCampaignResponse startNow(EmailCampaign campaign) {
        Long campaignId = campaign.getId();
        List<EmailCampaignRecipient> pending =
                recipientRepository.findByCampaignIdAndStatus(campaignId, EmailRecipientStatus.PENDING);
        if (pending.isEmpty()) {
            throw new BadRequestException("The campaign has no recipients yet");
        }

        campaign.setScheduledAt(null);
        campaign.setStatus(EmailCampaignStatus.RUNNING);
        campaign.setStartedAt(Instant.now());
        campaign.setQueuedCount(pending.size());
        campaign = campaignRepository.save(campaign);

        List<Long> fallback = new ArrayList<>();
        for (EmailCampaignRecipient recipient : pending) {
            recipient.setStatus(EmailRecipientStatus.QUEUED);
            recipientRepository.save(recipient);
            String payload = "{\"recipientId\":" + recipient.getId() + "}";
            if (!eventPublisher.publishCampaignSend(campaignId, payload)) {
                fallback.add(recipient.getId());
            }
        }
        if (!fallback.isEmpty()) {
            log.warn("Kafka unavailable — {} email campaign sends running on emailExecutor instead",
                    fallback.size());
            processRecipientsAsync(fallback);
        }
        indexCampaign(campaign);
        return toResponse(campaign);
    }

    @Transactional
    public EmailCampaignResponse cancel(Long campaignId) {
        EmailCampaign campaign = requireMyCampaign(campaignId);
        if (campaign.getStatus() == EmailCampaignStatus.COMPLETED
                || campaign.getStatus() == EmailCampaignStatus.CANCELLED) {
            return toResponse(campaign);
        }
        campaign.setStatus(EmailCampaignStatus.CANCELLED);
        campaign.setCompletedAt(Instant.now());
        return toResponse(campaignRepository.save(campaign));
    }

    @Transactional
    public EmailCampaignResponse pause(Long campaignId) {
        EmailCampaign campaign = requireMyCampaign(campaignId);
        if (campaign.getStatus() != EmailCampaignStatus.RUNNING) {
            throw new BadRequestException("Only a running campaign can be paused");
        }
        campaign.setStatus(EmailCampaignStatus.PAUSED);
        return toResponse(campaignRepository.save(campaign));
    }

    /** Re-queues everything still QUEUED — idempotent workers make this safe. */
    @Transactional
    public EmailCampaignResponse resume(Long campaignId) {
        EmailCampaign campaign = requireMyCampaign(campaignId);
        if (campaign.getStatus() != EmailCampaignStatus.PAUSED) {
            throw new BadRequestException("Only a paused campaign can be resumed");
        }
        campaign.setStatus(EmailCampaignStatus.RUNNING);
        campaign = campaignRepository.save(campaign);

        List<Long> fallback = new ArrayList<>();
        for (EmailCampaignRecipient recipient :
                recipientRepository.findByCampaignIdAndStatus(campaignId, EmailRecipientStatus.QUEUED)) {
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

    @Async("emailExecutor")
    public void processRecipientsAsync(List<Long> recipientIds) {
        recipientIds.forEach(this::processRecipient);
    }

    /**
     * Sends one recipient. Idempotent: only a QUEUED recipient is processed,
     * so Kafka redeliveries and the inline fallback can overlap safely.
     */
    public void processRecipient(Long recipientId) {
        EmailCampaignRecipient recipient = recipientRepository.findById(recipientId).orElse(null);
        if (recipient == null || recipient.getStatus() != EmailRecipientStatus.QUEUED) {
            return;
        }
        EmailCampaign campaign = campaignRepository.findById(recipient.getCampaignId()).orElse(null);
        if (campaign == null) return;

        if (campaign.getStatus() == EmailCampaignStatus.CANCELLED) {
            failRecipient(recipient, campaign, "Campaign cancelled");
            return;
        }
        if (campaign.getStatus() == EmailCampaignStatus.PAUSED
                || campaign.getStatus() == EmailCampaignStatus.SCHEDULED) {
            return; // stays QUEUED — resume() re-publishes it
        }
        // The sender is the org's own mailbox; if it was removed mid-campaign
        // the remaining recipients fail visibly instead of going out from
        // the platform address.
        if (smtpSettingsRepository.findByOwnerUserId(campaign.getOwnerUserId()).isEmpty()) {
            failRecipient(recipient, campaign, "Email account not configured (Profile → Email)");
            return;
        }

        try {
            Map<String, Object> payload = readJson(recipient.getPayloadJson());
            String subject = PlaceholderResolver.resolve(campaign.getSubject(), payload);
            String body = PlaceholderResolver.resolve(campaign.getBody(), payload);

            recipient.setAttemptCount(recipient.getAttemptCount() + 1);
            recipient.setLastAttemptAt(Instant.now());
            recipientRepository.save(recipient);

            waitForRateSlot(campaign.getOwnerUserId());
            orgSmtpService.sendAs(campaign.getOwnerUserId(), recipient.getEmail(), subject, body);

            recipient.setStatus(EmailRecipientStatus.SENT);
            recipient.setError(null);
            recipientRepository.save(recipient);
            campaignRepository.markOneSent(campaign.getId());
        } catch (Exception e) {
            log.error("Email campaign recipient {} failed: {}", recipientId, e.getMessage());
            recipient.setStatus(EmailRecipientStatus.FAILED);
            recipient.setError(truncate(rootMessage(e), 500));
            recipientRepository.save(recipient);
            campaignRepository.markOneFailed(campaign.getId());
        }
        maybeComplete(campaign.getId());
    }

    /**
     * Blocks the worker (never an HTTP thread) until a send slot is free —
     * the same pacing the WhatsApp module uses, keyed per owner so one org's
     * blast never slows another's.
     */
    private void waitForRateSlot(String ownerUserId) {
        int limit = Math.max(1, properties.getSendsPerMinute());
        for (int i = 0; i < 180; i++) {
            if (rateLimiter.allow("email:" + ownerUserId, limit, Duration.ofMinutes(1))) {
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

    private void failRecipient(EmailCampaignRecipient recipient, EmailCampaign campaign, String reason) {
        recipient.setStatus(EmailRecipientStatus.FAILED);
        recipient.setError(reason);
        recipientRepository.save(recipient);
        campaignRepository.markOneFailed(campaign.getId());
        maybeComplete(campaign.getId());
    }

    private void maybeComplete(Long campaignId) {
        long open = recipientRepository.countByCampaignIdAndStatusIn(campaignId,
                List.of(EmailRecipientStatus.PENDING, EmailRecipientStatus.QUEUED));
        if (open > 0) return;
        campaignRepository.findById(campaignId).ifPresent(campaign -> {
            if (campaign.getStatus() != EmailCampaignStatus.RUNNING) return;
            campaign.setStatus(campaign.getFailedCount() > 0
                    ? (campaign.getSentCount() > 0 ? EmailCampaignStatus.PARTIAL : EmailCampaignStatus.FAILED)
                    : EmailCampaignStatus.COMPLETED);
            campaign.setCompletedAt(Instant.now());
            campaignRepository.save(campaign);
            indexCampaign(campaign);
        });
    }

    /* -------------------------------------------------------------- reads */

    public Page<EmailCampaignResponse> list(int page, int size) {
        return campaignRepository.findByOwnerUserIdOrderByIdDesc(currentUserId(),
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)))
                .map(EmailCampaignService::toResponse);
    }

    public EmailCampaignResponse get(Long campaignId) {
        return toResponse(requireMyCampaign(campaignId));
    }

    public Page<EmailCampaignRecipientResponse> recipients(Long campaignId, int page, int size, String status) {
        EmailCampaign campaign = requireMyCampaign(campaignId);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200));
        Page<EmailCampaignRecipient> result;
        if (status != null && !status.isBlank()) {
            EmailRecipientStatus filter;
            try {
                filter = EmailRecipientStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown recipient status: " + status);
            }
            result = recipientRepository.findByCampaignIdAndStatusOrderByIdAsc(
                    campaign.getId(), filter, pageable);
        } else {
            result = recipientRepository.findByCampaignIdOrderByIdAsc(campaign.getId(), pageable);
        }
        return result.map(r -> EmailCampaignRecipientResponse.builder()
                .id(r.getId())
                .email(r.getEmail())
                .recordId(r.getRecordId())
                .contactId(r.getContactId())
                .status(r.getStatus().name())
                .attemptCount(r.getAttemptCount())
                .error(r.getError())
                .build());
    }

    /* ------------------------------------------------------------ helpers */

    private String currentUserId() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new UnauthorizedException("Not authenticated");
        return id.toString();
    }

    /** No platform-mailbox fallback here on purpose — see the class comment. */
    private void requireOwnSmtp(String ownerUserId) {
        if (smtpSettingsRepository.findByOwnerUserId(ownerUserId).isEmpty()) {
            throw new BadRequestException(
                    "Add your email account under Profile → Email (SMTP) first — campaigns go out from your own address.");
        }
    }

    private EmailCampaign requireMyCampaign(Long campaignId) {
        return campaignRepository.findByIdAndOwnerUserId(campaignId, currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found"));
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

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "Send failed" : message;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /** Campaign metadata only — never message bodies with customer data. */
    private void indexCampaign(EmailCampaign campaign) {
        try {
            String content = "Email campaign '" + campaign.getName() + "'"
                    + ". Subject: " + campaign.getSubject()
                    + ". Status: " + campaign.getStatus()
                    + ". Audience: " + campaign.getTargetDescription()
                    + ". Recipients: " + campaign.getTotalCount()
                    + ", sent " + campaign.getSentCount()
                    + ", failed " + campaign.getFailedCount() + ".";
            knowledgeIndexer.reindexEntity("email-campaign", campaign.getId(), content,
                    UUID.fromString(campaign.getOwnerUserId()));
        } catch (Exception e) {
            log.debug("Email campaign knowledge indexing skipped: {}", e.getMessage());
        }
    }

    public static EmailCampaignResponse toResponse(EmailCampaign campaign) {
        return EmailCampaignResponse.builder()
                .id(campaign.getId())
                .name(campaign.getName())
                .subject(campaign.getSubject())
                .body(campaign.getBody())
                .sourceType(campaign.getSourceType().name())
                .status(campaign.getStatus().name())
                .targetDescription(campaign.getTargetDescription())
                .totalCount(campaign.getTotalCount())
                .queuedCount(campaign.getQueuedCount())
                .sentCount(campaign.getSentCount())
                .failedCount(campaign.getFailedCount())
                .createdAt(campaign.getCreatedAt())
                .scheduledAt(campaign.getScheduledAt())
                .startedAt(campaign.getStartedAt())
                .completedAt(campaign.getCompletedAt())
                .build();
    }
}
