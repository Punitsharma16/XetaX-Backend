package com.xetax.crm.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.whatsapp.client.MetaWhatsAppClient;
import com.xetax.crm.whatsapp.client.WhatsAppProviderException;
import com.xetax.crm.whatsapp.dto.TemplateButton;
import com.xetax.crm.whatsapp.dto.TemplateCard;
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
        // A list, not a lookup: with no connected number there are simply no
        // templates. Every page with a WhatsApp composer asks for this on load,
        // and a 404 here showed up as a failed call on contacts/records for
        // every workspace that has not connected WhatsApp.
        WhatsAppConfig config = configService.myConfig()
                .filter(c -> c.getStatus() == com.xetax.crm.whatsapp.enums.WhatsAppConnectionStatus.CONNECTED)
                .orElse(null);
        if (config == null) return List.of();
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
        template.setHeaderFormat(request.getHeaderFormat() == null || request.getHeaderFormat().isBlank()
                ? (request.getHeaderText() == null || request.getHeaderText().isBlank() ? "NONE" : "TEXT")
                : request.getHeaderFormat().trim().toUpperCase());
        template.setHeaderMediaUrl(request.getHeaderMediaUrl());
        if (request.getCards() != null && !request.getCards().isEmpty()) {
            try {
                template.setCardMediaJson(objectMapper.writeValueAsString(
                        request.getCards().stream().map(TemplateCard::getHeaderMediaUrl).toList()));
            } catch (Exception ignored) { }
        }
        try {
            template.setComponentsJson(objectMapper.writeValueAsString(payload.get("components")));
        } catch (Exception ignored) { }
        template.setSyncedAt(Instant.now());
        template = templateRepository.save(template);

        return toResponse(template);
    }

    /**
     * Uploads the sample media a reviewer will see and returns Meta's handle.
     * The handle is short-lived and is only used while submitting a template.
     */
    public String uploadSample(byte[] bytes, String filename, String mimeType) {
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException("The sample file is empty");
        }
        if (bytes.length > 5 * 1024 * 1024) {
            throw new BadRequestException("Sample files must be 5MB or smaller");
        }
        String type = mimeType == null || mimeType.isBlank()
                ? "application/octet-stream" : mimeType;
        if (!type.startsWith("image/") && !type.startsWith("video/") && !"application/pdf".equals(type)) {
            throw new BadRequestException("Header samples must be an image, a video or a PDF");
        }
        try {
            return client.uploadTemplateSample(bytes, filename, type);
        } catch (WhatsAppProviderException e) {
            throw new BadRequestException("Meta rejected the sample: " + e.getUserMessage());
        }
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

        checkVariables("The body", body, true);
        if (request.getFooterText() != null && request.getFooterText().contains("{{")) {
            throw new BadRequestException("The footer can't contain variables");
        }

        List<java.util.Map<String, Object>> components = new java.util.ArrayList<>();
        java.util.Map<String, Object> header = headerComponent(
                request.getHeaderFormat(), request.getHeaderText(), request.getHeaderHandle(),
                request.getHeaderExample());
        if (header != null) components.add(header);

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

        java.util.Map<String, Object> buttons = buttonsComponent(request.getButtons());
        if (buttons != null) components.add(buttons);

        // A carousel is a BODY plus its cards; the cards carry their own
        // header, body and buttons, and Meta insists they all match.
        if (request.getCards() != null && !request.getCards().isEmpty()) {
            components.add(carouselComponent(request.getCards()));
        }
        return components;
    }

    /**
     * TEXT, a media header, or nothing.
     *
     * <p>A media header is submitted without any real media: the reviewer sees
     * the uploaded sample through `header_handle`, and each send supplies the
     * actual image. That is why a media header needs a handle and a TEXT one
     * does not.
     */
    private java.util.Map<String, Object> headerComponent(String format, String text, String handle,
                                                          String example) {
        String fmt = format == null || format.isBlank() ? "TEXT" : format.trim().toUpperCase();
        if ("NONE".equals(fmt)) return null;

        if ("TEXT".equals(fmt)) {
            if (text == null || text.isBlank()) return null;
            if (text.trim().length() > 60) {
                throw new BadRequestException("Header text cannot be longer than 60 characters");
            }
            int vars = variableCountOf(text);
            if (vars == 0) {
                return java.util.Map.of("type", "HEADER", "format", "TEXT", "text", text.trim());
            }
            // Meta allows a single variable in a text header, and wants a sample for it.
            checkVariables("The header", text, false);
            if (vars > 1) throw new BadRequestException("A text header can have only one variable, {{1}}");
            if (example == null || example.isBlank()) {
                throw new BadRequestException("The header uses {{1}} — give an example value for it");
            }
            java.util.Map<String, Object> node = new java.util.LinkedHashMap<>();
            node.put("type", "HEADER");
            node.put("format", "TEXT");
            node.put("text", text.trim());
            node.put("example", java.util.Map.of("header_text", List.of(example.trim())));
            return node;
        }
        if (!List.of("IMAGE", "VIDEO", "DOCUMENT").contains(fmt)) {
            throw new BadRequestException("Header format must be TEXT, IMAGE, VIDEO, DOCUMENT or NONE");
        }
        if (handle == null || handle.isBlank()) {
            throw new BadRequestException(
                    "A " + fmt.toLowerCase() + " header needs a sample file — upload one first");
        }
        return java.util.Map.of("type", "HEADER", "format", fmt,
                "example", java.util.Map.of("header_handle", List.of(handle)));
    }

    /**
     * Meta's button rules, checked here rather than letting the Graph API
     * answer with one of its opaque generic errors: at most 10 buttons, at
     * most 2 of them URL, at most 1 phone number.
     */
    private java.util.Map<String, Object> buttonsComponent(List<TemplateButton> buttons) {
        if (buttons == null || buttons.isEmpty()) return null;
        if (buttons.size() > 10) {
            throw new BadRequestException("A template can have at most 10 buttons");
        }
        int urlCount = 0;
        int phoneCount = 0;
        List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();

        for (TemplateButton button : buttons) {
            String type = button.getType() == null ? "" : button.getType().trim().toUpperCase();
            String text = button.getText() == null ? "" : button.getText().trim();
            if (text.isBlank()) throw new BadRequestException("Every button needs a label");
            if (text.length() > 25) {
                throw new BadRequestException("Button label '" + text + "' is longer than 25 characters");
            }
            switch (type) {
                case "URL" -> {
                    if (++urlCount > 2) {
                        throw new BadRequestException("A template can have at most 2 URL buttons");
                    }
                    String url = button.getUrl() == null ? "" : button.getUrl().trim();
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        throw new BadRequestException("URL button '" + text + "' needs a full https:// link");
                    }
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("type", "URL");
                    row.put("text", text);
                    row.put("url", url);
                    if (url.contains("{{1}}")) {
                        String sample = button.getUrlExample();
                        if (sample == null || sample.isBlank()) {
                            throw new BadRequestException(
                                    "URL button '" + text + "' uses {{1}} — give an example link");
                        }
                        row.put("example", List.of(sample.trim()));
                    }
                    rows.add(row);
                }
                case "PHONE_NUMBER" -> {
                    if (++phoneCount > 1) {
                        throw new BadRequestException("A template can have only one phone number button");
                    }
                    String phone = button.getPhoneNumber() == null ? "" : button.getPhoneNumber().trim();
                    if (phone.isBlank()) {
                        throw new BadRequestException("Phone button '" + text + "' needs a number");
                    }
                    rows.add(java.util.Map.of("type", "PHONE_NUMBER", "text", text,
                            "phone_number", phone.startsWith("+") ? phone : "+" + phone));
                }
                case "QUICK_REPLY" -> rows.add(java.util.Map.of("type", "QUICK_REPLY", "text", text));
                case "FLOW" -> {
                    String flowId = button.getFlowId() == null ? "" : button.getFlowId().trim();
                    if (flowId.isBlank()) {
                        throw new BadRequestException("Flow button '" + text + "' needs a published Flow");
                    }
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("type", "FLOW");
                    row.put("text", text);
                    row.put("flow_id", flowId);
                    row.put("flow_action", button.getFlowAction() == null || button.getFlowAction().isBlank()
                            ? "navigate" : button.getFlowAction().trim().toLowerCase());
                    row.put("navigate_screen", "FIRST_ENTRY_SCREEN");
                    rows.add(row);
                }
                default -> throw new BadRequestException(
                        "Button type must be URL, PHONE_NUMBER, QUICK_REPLY or FLOW");
            }
        }
        return java.util.Map.of("type", "BUTTONS", "buttons", rows);
    }

    /**
     * A carousel: two to ten cards that all look alike. Meta rejects a set
     * whose cards differ in header format or button shape, so that is checked
     * against the first card before anything is submitted.
     */
    private java.util.Map<String, Object> carouselComponent(List<TemplateCard> cards) {
        if (cards.size() < 2 || cards.size() > 10) {
            throw new BadRequestException("A carousel needs between 2 and 10 cards");
        }
        TemplateCard first = cards.get(0);
        String sharedFormat = cardFormat(first);
        int sharedButtons = first.getButtons() == null ? 0 : first.getButtons().size();

        List<java.util.Map<String, Object>> cardNodes = new java.util.ArrayList<>();
        int index = 0;
        for (TemplateCard card : cards) {
            if (!sharedFormat.equals(cardFormat(card))) {
                throw new BadRequestException(
                        "Every carousel card must use the same header type (" + sharedFormat + ")");
            }
            int buttonCount = card.getButtons() == null ? 0 : card.getButtons().size();
            if (buttonCount != sharedButtons) {
                throw new BadRequestException(
                        "Every carousel card must have the same number of buttons (" + sharedButtons + ")");
            }
            if (card.getBodyText() == null || card.getBodyText().isBlank()) {
                throw new BadRequestException("Carousel card " + (index + 1) + " needs body text");
            }
            checkVariables("Carousel card " + (index + 1) + " body", card.getBodyText(), true);
            if (card.getHeaderHandle() == null || card.getHeaderHandle().isBlank()) {
                throw new BadRequestException(
                        "Carousel card " + (index + 1) + " needs a sample image — upload one first");
            }

            List<java.util.Map<String, Object>> cardComponents = new java.util.ArrayList<>();
            cardComponents.add(java.util.Map.of("type", "HEADER", "format", sharedFormat,
                    "example", java.util.Map.of("header_handle", List.of(card.getHeaderHandle()))));

            java.util.Map<String, Object> cardBody = new java.util.LinkedHashMap<>();
            cardBody.put("type", "BODY");
            cardBody.put("text", card.getBodyText().trim());
            int vars = variableCountOf(card.getBodyText());
            if (vars > 0) {
                List<String> cardExamples = card.getExampleParams() == null
                        ? List.of() : card.getExampleParams();
                if (cardExamples.size() < vars) {
                    throw new BadRequestException("Carousel card " + (index + 1) + " uses {{" + vars
                            + "}} — provide " + vars + " example value(s)");
                }
                cardBody.put("example", java.util.Map.of(
                        "body_text", List.of(cardExamples.subList(0, vars))));
            }
            cardComponents.add(cardBody);

            java.util.Map<String, Object> cardButtons = buttonsComponent(card.getButtons());
            if (cardButtons != null) cardComponents.add(cardButtons);

            cardNodes.add(java.util.Map.of("card_index", index, "components", cardComponents));
            index++;
        }
        return java.util.Map.of("type", "CAROUSEL", "cards", cardNodes);
    }

    private static String cardFormat(TemplateCard card) {
        String fmt = card.getHeaderFormat() == null || card.getHeaderFormat().isBlank()
                ? "IMAGE" : card.getHeaderFormat().trim().toUpperCase();
        if (!List.of("IMAGE", "VIDEO").contains(fmt)) {
            throw new BadRequestException("Carousel cards can only use an IMAGE or VIDEO header");
        }
        return fmt;
    }

    /**
     * Meta's rules for variables, checked before submitting because its own
     * rejection only says the template is invalid: numbered {{1}}, {{2}}…
     * with no gaps, never two side by side, and — in a body — not at the very
     * start or end of the text.
     */
    static void checkVariables(String where, String text, boolean noEdges) {
        if (text == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{(\\d+)}}").matcher(text);
        java.util.Set<Integer> seen = new java.util.TreeSet<>();
        while (m.find()) seen.add(Integer.parseInt(m.group(1)));
        if (seen.isEmpty()) return;
        int max = java.util.Collections.max(seen);
        for (int i = 1; i <= max; i++) {
            if (!seen.contains(i)) {
                throw new BadRequestException(where + " variables must be numbered {{1}}, {{2}}… with no gaps — {{"
                        + i + "}} is missing");
            }
        }
        String trimmed = text.trim();
        if (noEdges && (trimmed.startsWith("{{") || trimmed.endsWith("}}"))) {
            throw new BadRequestException(where + " can't start or end with a variable — add some words around it");
        }
        if (java.util.regex.Pattern.compile("}}\\s*\\{\\{").matcher(text).find()) {
            throw new BadRequestException(where + " has two variables side by side — put some text between them");
        }
    }

    static int variableCountOf(String text) {
        if (text == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{(\\d+)}}").matcher(text);
        int max = 0;
        while (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        return max;
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
                .headerFormat(template.getHeaderFormat())
                .headerMediaUrl(template.getHeaderMediaUrl())
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
