package com.xetax.crm.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.whatsapp.client.MetaWhatsAppClient;
import com.xetax.crm.whatsapp.client.WhatsAppProviderException;
import com.xetax.crm.whatsapp.dto.TemplateCreateRequest;
import com.xetax.crm.whatsapp.dto.WhatsAppTemplateResponse;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppTemplate;
import com.xetax.crm.whatsapp.repository.WhatsAppTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Local read-model of the WABA's Meta templates. Meta stays source of truth. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppTemplateService {

    private final WhatsAppTemplateRepository templateRepository;
    private final MetaWhatsAppClient client;
    private final SecretEncryptionService encryption;
    private final WhatsAppConfigService configService;
    private final ObjectMapper objectMapper;
    private final KnowledgeIndexer knowledgeIndexer;

    @Transactional
    public int syncTemplates(WhatsAppConfig config) {
        String token = encryption.decrypt(config.getAccessTokenEncrypted());
        JsonNode result = client.getTemplates(config.getWabaId(), token);

        int count = 0;
        for (JsonNode node : result.path("data")) {
            String name = node.path("name").asText();
            String language = node.path("language").asText("en");
            WhatsAppTemplate template = templateRepository
                    .findByWhatsappConfigIdAndNameAndLanguage(config.getId(), name, language)
                    .orElseGet(WhatsAppTemplate::new);
            template.setOwnerUserId(config.getOwnerUserId());
            template.setWhatsappConfigId(config.getId());
            template.setMetaTemplateId(node.path("id").asText(null));
            template.setName(name);
            template.setLanguage(language);
            template.setCategory(node.path("category").asText(null));
            template.setStatus(node.path("status").asText(null));
            template.setQualityScore(node.path("quality_score").path("score").asText(null));
            try {
                template.setComponentsJson(objectMapper.writeValueAsString(node.path("components")));
            } catch (Exception ignored) { }
            template.setSyncedAt(Instant.now());
            templateRepository.save(template);
            count++;
        }
        indexTemplateSummary(config, count);
        return count;
    }

    public List<WhatsAppTemplateResponse> myTemplates() {
        WhatsAppConfig config = configService.requireConnectedConfig();
        return templateRepository
                .findByOwnerUserIdAndWhatsappConfigIdOrderByNameAsc(config.getOwnerUserId(), config.getId())
                .stream()
                .map(WhatsAppTemplateService::toResponse)
                .toList();
    }

    /**
     * Submits a new template to Meta for approval and mirrors it locally.
     * The initial status comes from Meta (usually PENDING; APPROVED can be
     * instant for some UTILITY templates) — later reviews land via syncTemplates.
     */
    @Transactional
    public WhatsAppTemplateResponse createTemplate(TemplateCreateRequest request) {
        WhatsAppConfig config = configService.requireConnectedConfig();

        String name = normalizeName(request.getName());
        String category = request.getCategory() == null ? "" : request.getCategory().trim().toUpperCase();
        if (!List.of("MARKETING", "UTILITY", "AUTHENTICATION").contains(category)) {
            throw new BadRequestException("category must be MARKETING, UTILITY or AUTHENTICATION");
        }
        String language = request.getLanguage() == null || request.getLanguage().isBlank()
                ? "en" : request.getLanguage().trim();

        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("name", name);
        payload.put("category", category);
        payload.put("language", language);
        payload.put("components", "AUTHENTICATION".equals(category)
                ? authenticationComponents()
                : contentComponents(request));

        String token = encryption.decrypt(config.getAccessTokenEncrypted());
        com.fasterxml.jackson.databind.JsonNode created;
        try {
            created = client.createTemplate(config.getWabaId(), token, payload);
        } catch (WhatsAppProviderException e) {
            throw new BadRequestException("Meta rejected the template: " + e.getUserMessage());
        }

        WhatsAppTemplate template = templateRepository
                .findByWhatsappConfigIdAndNameAndLanguage(config.getId(), name, language)
                .orElseGet(WhatsAppTemplate::new);
        template.setOwnerUserId(config.getOwnerUserId());
        template.setWhatsappConfigId(config.getId());
        template.setMetaTemplateId(created.path("id").asText(null));
        template.setName(name);
        template.setLanguage(language);
        template.setCategory(created.path("category").asText(category));
        template.setStatus(created.path("status").asText("PENDING"));
        try {
            template.setComponentsJson(objectMapper.writeValueAsString(payload.get("components")));
        } catch (Exception ignored) { }
        template.setSyncedAt(Instant.now());
        template = templateRepository.save(template);

        return toResponse(template);
    }

    /** Removes the template from Meta (all languages) and locally. */
    @Transactional
    public void deleteTemplate(String name) {
        WhatsAppConfig config = configService.requireConnectedConfig();
        String normalized = normalizeName(name);
        String token = encryption.decrypt(config.getAccessTokenEncrypted());
        try {
            client.deleteTemplate(config.getWabaId(), token, normalized);
        } catch (WhatsAppProviderException e) {
            throw new BadRequestException("Could not delete the template: " + e.getUserMessage());
        }
        templateRepository
                .findByOwnerUserIdAndWhatsappConfigIdOrderByNameAsc(config.getOwnerUserId(), config.getId())
                .stream()
                .filter(t -> t.getName().equals(normalized))
                .forEach(templateRepository::delete);
    }

    /** Meta template names: lowercase letters, digits, underscores only. */
    private String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Template name is required");
        }
        String name = raw.trim().toLowerCase()
                .replaceAll("[^a-z0-9_ ]", "")
                .replaceAll("\\s+", "_");
        if (name.isBlank() || name.length() > 512) {
            throw new BadRequestException("Template name must use letters, numbers and underscores");
        }
        return name;
    }

    /** HEADER/BODY/FOOTER components for MARKETING and UTILITY templates. */
    private List<java.util.Map<String, Object>> contentComponents(TemplateCreateRequest request) {
        String body = request.getBodyText();
        if (body == null || body.isBlank()) {
            throw new BadRequestException("Body text is required");
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{\\{(\\d+)}}").matcher(body);
        int variableCount = 0;
        while (matcher.find()) {
            variableCount = Math.max(variableCount, Integer.parseInt(matcher.group(1)));
        }
        List<String> examples = request.getExampleParams() == null
                ? List.of() : request.getExampleParams();
        if (variableCount > 0 && examples.size() < variableCount) {
            throw new BadRequestException("Body uses {{" + variableCount + "}} variables — provide "
                    + variableCount + " example value(s); Meta rejects templates without examples");
        }

        List<java.util.Map<String, Object>> components = new java.util.ArrayList<>();
        if (request.getHeaderText() != null && !request.getHeaderText().isBlank()) {
            components.add(java.util.Map.of(
                    "type", "HEADER", "format", "TEXT", "text", request.getHeaderText().trim()));
        }
        java.util.Map<String, Object> bodyComponent = new java.util.LinkedHashMap<>();
        bodyComponent.put("type", "BODY");
        bodyComponent.put("text", body.trim());
        if (variableCount > 0) {
            bodyComponent.put("example", java.util.Map.of(
                    "body_text", List.of(examples.subList(0, variableCount))));
        }
        components.add(bodyComponent);
        if (request.getFooterText() != null && !request.getFooterText().isBlank()) {
            components.add(java.util.Map.of("type", "FOOTER", "text", request.getFooterText().trim()));
        }
        return components;
    }

    /** Meta-mandated fixed OTP structure for AUTHENTICATION templates. */
    private List<java.util.Map<String, Object>> authenticationComponents() {
        return List.of(
                java.util.Map.of("type", "BODY", "add_security_recommendation", true),
                java.util.Map.of("type", "FOOTER", "code_expiration_minutes", 10),
                java.util.Map.of("type", "BUTTONS", "buttons",
                        List.of(java.util.Map.of("type", "OTP", "otp_type", "COPY_CODE")))
        );
    }

    private static WhatsAppTemplateResponse toResponse(WhatsAppTemplate template) {
        return WhatsAppTemplateResponse.builder()
                .id(template.getId())
                .name(template.getName())
                .language(template.getLanguage())
                .category(template.getCategory())
                .status(template.getStatus())
                .componentsJson(template.getComponentsJson())
                .rejectionReason(template.getRejectionReason())
                .build();
    }

    /** Metadata only — template names/statuses. Never message content secrets. */
    private void indexTemplateSummary(WhatsAppConfig config, int count) {
        try {
            String names = templateRepository
                    .findByOwnerUserIdAndWhatsappConfigIdOrderByNameAsc(
                            config.getOwnerUserId(), config.getId())
                    .stream()
                    .map(t -> t.getName() + " (" + t.getLanguage() + ", " + t.getStatus() + ")")
                    .reduce((a, b) -> a + "; " + b).orElse("none");
            String content = "WhatsApp message templates synced for this user. Total: " + count
                    + ". Templates: " + names;
            knowledgeIndexer.reindexEntity("whatsapp-template", config.getId(), content,
                    UUID.fromString(config.getOwnerUserId()));
        } catch (Exception e) {
            log.debug("Template knowledge indexing skipped: {}", e.getMessage());
        }
    }
}
