package com.xetax.crm.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.whatsapp.client.MetaWhatsAppClient;
import com.xetax.crm.whatsapp.client.WhatsAppProviderException;
import com.xetax.crm.whatsapp.dto.ManualConnectRequest;
import com.xetax.crm.whatsapp.dto.OnboardingCompleteRequest;
import com.xetax.crm.whatsapp.dto.WhatsAppConfigResponse;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.enums.WhatsAppConnectionStatus;
import com.xetax.crm.whatsapp.repository.WhatsAppConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Turns an Embedded Signup result (or a manually pasted system-user token)
 * into a CONNECTED WhatsAppConfig: exchange code → find WABA → subscribe app
 * → register number → pull phone details → sync templates. Tokens are AES
 * encrypted before they touch the database and never logged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppOnboardingService {

    private final MetaWhatsAppClient client;
    private final WhatsAppConfigRepository configRepository;
    private final WhatsAppConfigService configService;
    private final SecretEncryptionService encryption;
    private final WhatsAppTemplateService templateService;
    private final KnowledgeIndexer knowledgeIndexer;

    @Transactional
    public WhatsAppConfigResponse completeEmbeddedSignup(OnboardingCompleteRequest request) {
        if (request.getCode() == null || request.getCode().isBlank()) {
            throw new BadRequestException("Signup code is required");
        }
        String token = client.exchangeCodeForToken(request.getCode());
        return connectWithToken(token, request.getWabaId(), request.getPhoneNumberId());
    }

    @Transactional
    public WhatsAppConfigResponse manualConnect(ManualConnectRequest request) {
        if (request.getAccessToken() == null || request.getAccessToken().isBlank()
                || request.getWabaId() == null || request.getWabaId().isBlank()
                || request.getPhoneNumberId() == null || request.getPhoneNumberId().isBlank()) {
            throw new BadRequestException("accessToken, wabaId and phoneNumberId are all required");
        }
        return connectWithToken(request.getAccessToken(), request.getWabaId(), request.getPhoneNumberId());
    }

    private WhatsAppConfigResponse connectWithToken(String token, String wabaIdHint, String phoneIdHint) {
        String ownerUserId = configService.currentUserId();

        String wabaId = wabaIdHint;
        if (wabaId == null || wabaId.isBlank()) {
            List<String> granted = client.findGrantedWabaIds(token);
            if (granted.isEmpty()) {
                throw new BadRequestException(
                        "No WhatsApp Business Account was shared. Please redo the signup and share a WABA.");
            }
            wabaId = granted.get(0);
        }

        // reuse the user's existing row so reconnects don't multiply configs
        WhatsAppConfig config = configRepository
                .findFirstByOwnerUserIdOrderByIdDesc(ownerUserId)
                .orElseGet(WhatsAppConfig::new);
        config.setOwnerUserId(ownerUserId);
        config.setWabaId(wabaId);
        config.setStatus(WhatsAppConnectionStatus.CONNECTING);
        config.setAccessTokenEncrypted(encryption.encrypt(token));
        config.setLastError(null);

        try {
            client.subscribeApp(wabaId, token);
            config.setWebhookSubscribed(true);

            JsonNode phones = client.getPhoneNumbers(wabaId, token);
            JsonNode chosen = null;
            for (JsonNode phone : phones.path("data")) {
                if (phoneIdHint != null && phoneIdHint.equals(phone.path("id").asText())) {
                    chosen = phone;
                    break;
                }
                if (chosen == null) chosen = phone;
            }
            if (chosen == null) {
                throw new BadRequestException(
                        "The WhatsApp Business Account has no phone numbers yet.");
            }

            config.setPhoneNumberId(chosen.path("id").asText());
            config.setDisplayPhoneNumber(chosen.path("display_phone_number").asText(null));
            config.setVerifiedName(chosen.path("verified_name").asText(null));
            config.setQualityRating(chosen.path("quality_rating").asText(null));
            config.setNameStatus(chosen.path("name_status").asText(null));
            config.setCodeVerificationStatus(chosen.path("code_verification_status").asText(null));
            config.setMessagingLimit(chosen.path("messaging_limit_tier").asText(null));

            client.registerPhone(config.getPhoneNumberId(), token, null);

            config.setStatus(WhatsAppConnectionStatus.CONNECTED);
            config.setConnectedAt(Instant.now());
            config = configRepository.save(config);

            try {
                templateService.syncTemplates(config);
            } catch (Exception e) {
                // template sync failing must not fail onboarding
                log.warn("Template sync after onboarding failed: {}", e.getMessage());
            }
            indexConnection(config);
            return WhatsAppConfigService.toResponse(config);

        } catch (WhatsAppProviderException e) {
            config.setStatus(WhatsAppConnectionStatus.ERROR);
            config.setLastError(e.getUserMessage());
            configRepository.save(config);
            throw new BadRequestException("WhatsApp connection failed: " + e.getUserMessage());
        }
    }

    /** Connection metadata only — the token is NEVER indexed. */
    private void indexConnection(WhatsAppConfig config) {
        try {
            String content = "WhatsApp Business connection for this user. Status: "
                    + config.getStatus() + ". Business phone: " + config.getDisplayPhoneNumber()
                    + " (" + config.getVerifiedName() + "). Quality rating: "
                    + config.getQualityRating() + ". Messages, conversations and campaigns can be"
                    + " sent from the WhatsApp section of the CRM.";
            knowledgeIndexer.reindexEntity("whatsapp", config.getId(), content,
                    java.util.UUID.fromString(config.getOwnerUserId()));
        } catch (Exception e) {
            log.debug("WhatsApp connection knowledge indexing skipped: {}", e.getMessage());
        }
    }
}
