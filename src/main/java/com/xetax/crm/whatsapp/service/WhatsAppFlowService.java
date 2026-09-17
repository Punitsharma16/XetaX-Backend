package com.xetax.crm.whatsapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.dto.FormResponse;
import com.xetax.crm.data_manager.dto.RecordRequest;
import com.xetax.crm.data_manager.service.FieldService;
import com.xetax.crm.data_manager.service.FormService;
import com.xetax.crm.data_manager.service.RecordService;
import com.xetax.crm.whatsapp.client.MetaWhatsAppClient;
import com.xetax.crm.whatsapp.client.WhatsAppProviderException;
import com.xetax.crm.whatsapp.dto.*;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppFlow;
import com.xetax.crm.whatsapp.entity.WhatsAppFlowResponse;
import com.xetax.crm.whatsapp.repository.WhatsAppFlowRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppFlowResponseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * WhatsApp Flows end to end: build, publish, send, and collect what customers
 * fill in.
 *
 * <p>The Flow lives at Meta and the answers arrive on the same webhook as a
 * message, so the work here is keeping the two in step — a local row per Flow
 * so the panel can list and edit them, and a token per send so a reply can be
 * traced back to the customer it belongs to.
 */
@Service
@Slf4j
public class WhatsAppFlowService {

    private static final List<String> CATEGORIES = List.of(
            "SIGN_UP", "SIGN_IN", "APPOINTMENT_BOOKING", "LEAD_GENERATION",
            "CONTACT_US", "CUSTOMER_SUPPORT", "SURVEY", "OTHER");

    private final WhatsAppFlowRepository flowRepository;
    private final WhatsAppFlowResponseRepository responseRepository;
    private final WhatsAppConfigService configService;
    private final MetaWhatsAppClient client;
    private final SecretEncryptionService encryption;
    private final ObjectMapper objectMapper;
    private final WhatsAppFlowBuilder flowBuilder;
    private final FormService formService;
    private final FieldService fieldService;
    private final WhatsAppMessagingService messagingService;

    /** Lazy: RecordService reaches back into WhatsApp for its own notifications. */
    private final RecordService recordService;

    @Autowired
    public WhatsAppFlowService(WhatsAppFlowRepository flowRepository,
                               WhatsAppFlowResponseRepository responseRepository,
                               WhatsAppConfigService configService,
                               MetaWhatsAppClient client,
                               SecretEncryptionService encryption,
                               ObjectMapper objectMapper,
                               WhatsAppFlowBuilder flowBuilder,
                               FormService formService,
                               FieldService fieldService,
                               WhatsAppMessagingService messagingService,
                               @Lazy RecordService recordService) {
        this.flowRepository = flowRepository;
        this.responseRepository = responseRepository;
        this.configService = configService;
        this.client = client;
        this.encryption = encryption;
        this.objectMapper = objectMapper;
        this.flowBuilder = flowBuilder;
        this.formService = formService;
        this.fieldService = fieldService;
        this.messagingService = messagingService;
        this.recordService = recordService;
    }

    public List<String> categories() {
        return CATEGORIES;
    }

    /* ------------------------------------------------------------ reading */

    public List<FlowView> myFlows() {
        String owner = configService.currentUserId();
        return flowRepository.findByOwnerUserIdOrderByIdDesc(owner).stream()
                .map(flow -> toView(flow, null))
                .toList();
    }

    public FlowView get(Long id) {
        return toView(require(id), null);
    }

    /* ------------------------------------------------------------ writing */

    @Transactional
    public FlowView create(FlowCreateRequest request) {
        WhatsAppConfig config = configService.requireConnectedConfig();
        String name = normalizeName(request.getName());
        if (flowRepository.findByOwnerUserIdAndName(config.getOwnerUserId(), name).isPresent()) {
            throw new BadRequestException("A Flow named '" + name + "' already exists");
        }
        List<String> categories = normalizeCategories(request.getCategories());
        String flowJson = resolveFlowJson(request, name);

        String token = encryption.decrypt(config.getAccessTokenEncrypted());
        JsonNode created;
        try {
            created = client.createFlow(config.getWabaId(), token, name, categories);
        } catch (WhatsAppProviderException e) {
            throw new BadRequestException("Meta rejected the Flow: " + e.getUserMessage());
        }
        String metaFlowId = created.path("id").asText(null);
        if (metaFlowId == null || metaFlowId.isBlank()) {
            throw new BadRequestException("Meta created no Flow id — please try again");
        }

        WhatsAppFlow flow = WhatsAppFlow.builder()
                .ownerUserId(config.getOwnerUserId())
                .whatsappConfigId(config.getId())
                .metaFlowId(metaFlowId)
                .name(name)
                .status("DRAFT")
                .category(categories.get(0))
                .flowJson(flowJson)
                .formId(request.getFormId())
                .fieldMapJson(writeJson(request.getFieldMap()))
                .build();
        flow = flowRepository.save(flow);

        List<String> errors = uploadScreens(flow, token, flowJson);
        if (errors.isEmpty() && request.isPublish()) {
            publishInternal(flow, token);
        }
        return toView(flowRepository.save(flow), errors);
    }

    /** Replaces the screens of a draft Flow. A published Flow cannot change. */
    @Transactional
    public FlowView updateScreens(Long id, FlowCreateRequest request) {
        WhatsAppFlow flow = require(id);
        if ("PUBLISHED".equals(flow.getStatus())) {
            throw new BadRequestException(
                    "A published Flow cannot be changed — create a new one instead");
        }
        WhatsAppConfig config = configService.requireConnectedConfig();
        String token = encryption.decrypt(config.getAccessTokenEncrypted());

        if (request.getFormId() != null) flow.setFormId(request.getFormId());
        if (request.getFieldMap() != null) flow.setFieldMapJson(writeJson(request.getFieldMap()));
        if (request.getName() != null && !request.getName().isBlank()) {
            String name = normalizeName(request.getName());
            try {
                client.updateFlowMetadata(flow.getMetaFlowId(), token, name,
                        normalizeCategories(request.getCategories()));
                flow.setName(name);
            } catch (WhatsAppProviderException e) {
                throw new BadRequestException("Meta rejected the change: " + e.getUserMessage());
            }
        }

        String flowJson = request.getFlowJson() != null && !request.getFlowJson().isBlank()
                ? request.getFlowJson()
                : resolveFlowJson(request, flow.getName());
        List<String> errors = uploadScreens(flow, token, flowJson);
        if (errors.isEmpty() && request.isPublish()) {
            publishInternal(flow, token);
        }
        return toView(flowRepository.save(flow), errors);
    }

    @Transactional
    public FlowView publish(Long id) {
        WhatsAppFlow flow = require(id);
        WhatsAppConfig config = configService.requireConnectedConfig();
        publishInternal(flow, encryption.decrypt(config.getAccessTokenEncrypted()));
        return toView(flowRepository.save(flow), null);
    }

    @Transactional
    public FlowView deprecate(Long id) {
        WhatsAppFlow flow = require(id);
        WhatsAppConfig config = configService.requireConnectedConfig();
        try {
            client.deprecateFlow(flow.getMetaFlowId(),
                    encryption.decrypt(config.getAccessTokenEncrypted()));
            flow.setStatus("DEPRECATED");
        } catch (WhatsAppProviderException e) {
            throw new BadRequestException("Meta refused: " + e.getUserMessage());
        }
        return toView(flowRepository.save(flow), null);
    }

    @Transactional
    public void delete(Long id) {
        WhatsAppFlow flow = require(id);
        WhatsAppConfig config = configService.requireConnectedConfig();
        try {
            client.deleteFlow(flow.getMetaFlowId(),
                    encryption.decrypt(config.getAccessTokenEncrypted()));
        } catch (WhatsAppProviderException e) {
            // A Flow already gone at Meta must not strand the local row.
            log.warn("Flow {} delete at Meta failed: {}", flow.getMetaFlowId(), e.getUserMessage());
        }
        flowRepository.delete(flow);
    }

    /** A link the panel can open to try the Flow before sending it to anyone. */
    public String previewUrl(Long id) {
        WhatsAppFlow flow = require(id);
        WhatsAppConfig config = configService.requireConnectedConfig();
        try {
            JsonNode node = client.flowPreview(flow.getMetaFlowId(),
                    encryption.decrypt(config.getAccessTokenEncrypted()));
            return node.path("preview").path("preview_url").asText(null);
        } catch (WhatsAppProviderException e) {
            throw new BadRequestException("Meta could not build a preview: " + e.getUserMessage());
        }
    }

    /* ------------------------------------------------------------ sending */

    /**
     * Sends a published Flow. The token minted here is what makes the reply
     * traceable, so it is stored before the message leaves.
     */
    @Transactional
    public FlowResponseView send(FlowSendRequest request) {
        WhatsAppFlow flow = require(request.getFlowId());
        if (!"PUBLISHED".equals(flow.getStatus())) {
            throw new BadRequestException("Publish the Flow before sending it");
        }
        String phone = messagingService.resolvePhoneForFlow(request.getPhone(), request.getConversationId());
        String flowToken = "flw_" + flow.getId() + "_" + UUID.randomUUID().toString().replace("-", "");

        WhatsAppFlowResponse pending = WhatsAppFlowResponse.builder()
                .ownerUserId(flow.getOwnerUserId())
                .flowId(flow.getId())
                .flowToken(flowToken)
                .customerPhone(phone)
                .conversationId(request.getConversationId())
                .recordId(request.getRecordId())
                .note("Sent — waiting for the customer to submit")
                .build();
        responseRepository.save(pending);

        messagingService.sendFlow(flow, phone, flowToken, request);
        return toResponseView(pending, flow);
    }

    /* --------------------------------------------------------- collecting */

    /**
     * A submitted Flow, straight off the webhook.
     *
     * <p>The answers are stored whatever happens. Only after that is a record
     * attempted, because a mapping mistake must cost a record, never the
     * customer's answers.
     */
    @Transactional
    public void recordSubmission(String ownerUserId, String flowToken, String customerPhone,
                                 Long conversationId, String responseJson) {
        Map<String, Object> answers = readAnswers(responseJson);

        // Only a token we minted names one send. Anything else — Meta's
        // "unused" for a send that carried no token, or a Flow built outside
        // XetaX — is shared by many customers, so reusing its row would
        // overwrite one customer's answers with the next one's.
        boolean ours = flowToken != null && flowToken.startsWith("flw_");
        WhatsAppFlowResponse row = ours
                ? responseRepository.findByFlowToken(flowToken)
                        .filter(existing -> Objects.equals(existing.getOwnerUserId(), ownerUserId))
                        .orElse(null)
                : null;
        if (row == null) {
            row = WhatsAppFlowResponse.builder()
                    .ownerUserId(ownerUserId)
                    .flowToken(flowToken)
                    .customerPhone(customerPhone)
                    .conversationId(conversationId)
                    .flowId(ours ? flowIdOf(flowToken, ownerUserId) : null)
                    .build();
        }
        row.setCustomerPhone(customerPhone != null ? customerPhone : row.getCustomerPhone());
        row.setConversationId(conversationId != null ? conversationId : row.getConversationId());
        row.setAnswersJson(writeJson(answers));
        row.setNote("Submitted");
        responseRepository.save(row);

        WhatsAppFlow flow = row.getFlowId() == null ? null
                : flowRepository.findById(row.getFlowId()).orElse(null);
        if (flow == null || flow.getFormId() == null) {
            return;   // answers kept; nothing was asked to be created
        }
        try {
            String recordId = createRecord(flow, answers, customerPhone);
            row.setRecordId(recordId);
            row.setNote("Submitted — record created");
        } catch (Exception e) {
            row.setNote("Submitted — could not create the record: " + cut(e.getMessage(), 300));
            log.warn("Flow {} submission could not create a record: {}", flow.getId(), e.getMessage());
        }
        responseRepository.save(row);
    }

    /** Our tokens read flw_{flowId}_{random}; the Flow must still belong to this workspace. */
    private Long flowIdOf(String flowToken, String ownerUserId) {
        String[] parts = flowToken.split("_");
        if (parts.length < 3) return null;
        try {
            Long id = Long.parseLong(parts[1]);
            return flowRepository.findById(id)
                    .filter(flow -> Objects.equals(flow.getOwnerUserId(), ownerUserId))
                    .map(WhatsAppFlow::getId)
                    .orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Page<FlowResponseView> responses(Long flowId, int page, int size) {
        String owner = configService.currentUserId();
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<WhatsAppFlowResponse> rows = flowId == null
                ? responseRepository.findByOwnerUserIdOrderByIdDesc(owner, pageable)
                : responseRepository.findByFlowIdAndOwnerUserIdOrderByIdDesc(flowId, owner, pageable);
        Map<Long, String> names = new HashMap<>();
        return rows.map(row -> {
            String name = row.getFlowId() == null ? null : names.computeIfAbsent(row.getFlowId(),
                    id -> flowRepository.findById(id).map(WhatsAppFlow::getName).orElse(null));
            return toResponseView(row, name);
        });
    }

    /* ------------------------------------------------------------ helpers */

    private void publishInternal(WhatsAppFlow flow, String token) {
        try {
            client.publishFlow(flow.getMetaFlowId(), token);
            flow.setStatus("PUBLISHED");
            flow.setPublishedAt(Instant.now());
            flow.setLastError(null);
        } catch (WhatsAppProviderException e) {
            flow.setLastError(cut(e.getUserMessage(), 500));
            throw new BadRequestException("Meta could not publish the Flow: " + e.getUserMessage());
        }
    }

    /**
     * Uploads the screens and returns whatever Meta complained about. Meta
     * answers 200 with a validation_errors array rather than failing, so an
     * empty list is the only real success.
     */
    private List<String> uploadScreens(WhatsAppFlow flow, String token, String flowJson) {
        try {
            JsonNode result = client.uploadFlowJson(flow.getMetaFlowId(), token, flowJson);
            List<String> errors = new ArrayList<>();
            for (JsonNode error : result.path("validation_errors")) {
                String message = error.path("message").asText("");
                String pointer = error.path("pointers").path(0).path("line_start").asText("");
                errors.add(pointer.isBlank() ? message : message + " (line " + pointer + ")");
            }
            flow.setFlowJson(flowJson);
            flow.setLastError(errors.isEmpty() ? null : cut(String.join("; ", errors), 500));
            return errors;
        } catch (WhatsAppProviderException e) {
            flow.setLastError(cut(e.getUserMessage(), 500));
            throw new BadRequestException("Meta rejected the screens: " + e.getUserMessage());
        }
    }

    /** Either the caller's own Flow JSON, or one generated from a CRM form. */
    private String resolveFlowJson(FlowCreateRequest request, String name) {
        if (request.getFlowJson() != null && !request.getFlowJson().isBlank()) {
            try {
                objectMapper.readTree(request.getFlowJson());
            } catch (Exception e) {
                throw new BadRequestException("The Flow JSON is not valid JSON");
            }
            return request.getFlowJson();
        }
        if (request.getFormId() == null) {
            throw new BadRequestException(
                    "Pick a form to generate the Flow from, or paste your own Flow JSON");
        }
        FormResponse form = formService.getById(request.getFormId());
        return flowBuilder.buildFromFields(form.getName(), fieldService.getAll(form.getId()));
    }

    /** Writes the answers into the linked form, honouring the field map. */
    private String createRecord(WhatsAppFlow flow, Map<String, Object> answers, String customerPhone) {
        FormResponse form = formService.getById(flow.getFormId());
        Map<String, String> map = readFieldMap(flow.getFieldMapJson());

        Map<String, Object> data = new LinkedHashMap<>();
        answers.forEach((key, value) -> {
            if (value == null) return;
            String target = map.getOrDefault(key, key);
            data.put(target, value);
        });
        // The customer's number is the one thing the Flow never asks for, so it
        // is filled in from the chat. WhatsApp gives it in international form
        // while a CRM phone field wants the national one, so it is converted —
        // and simply left out if it cannot be, since losing a phone number is
        // better than losing the whole submission to a validation error.
        String national = nationalPhone(customerPhone);
        if (national != null) {
            data.putIfAbsent("PHONE", national);
        }

        RecordRequest recordRequest = new RecordRequest();
        recordRequest.setData(data);
        return recordService.create(form.getSlug(), recordRequest).getId();
    }

    /**
     * 919876543210 → 9876543210. Drops the configured country code when that
     * leaves a ten-digit number, which is what a CRM phone field accepts.
     * Returns null when the number is not of that shape.
     */
    private String nationalPhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 10) return digits;
        String countryCode = configService.defaultCountryCode();
        if (countryCode != null && !countryCode.isBlank() && digits.startsWith(countryCode)) {
            String rest = digits.substring(countryCode.length());
            if (rest.length() == 10) return rest;
        }
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : null;
    }

    private Map<String, Object> readAnswers(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(responseJson,
                    new TypeReference<Map<String, Object>>() {});
            // Meta echoes its own bookkeeping back inside the payload.
            Map<String, Object> clean = new LinkedHashMap<>(raw);
            clean.remove("flow_token");
            return clean;
        } catch (Exception e) {
            log.warn("Could not read a Flow response: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> readFieldMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private WhatsAppFlow require(Long id) {
        String owner = configService.currentUserId();
        return flowRepository.findByIdAndOwnerUserId(id, owner)
                .orElseThrow(() -> new ResourceNotFoundException("Flow not found"));
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) throw new BadRequestException("The Flow needs a name");
        String trimmed = name.trim();
        if (trimmed.length() > 200) throw new BadRequestException("The Flow name is too long");
        return trimmed;
    }

    private List<String> normalizeCategories(List<String> categories) {
        if (categories == null || categories.isEmpty()) return List.of("LEAD_GENERATION");
        List<String> clean = categories.stream()
                .filter(Objects::nonNull)
                .map(c -> c.trim().toUpperCase())
                .filter(CATEGORIES::contains)
                .distinct()
                .toList();
        if (clean.isEmpty()) {
            throw new BadRequestException("Category must be one of " + String.join(", ", CATEGORIES));
        }
        return clean;
    }

    private FlowView toView(WhatsAppFlow flow, List<String> validationErrors) {
        return FlowView.builder()
                .id(flow.getId())
                .metaFlowId(flow.getMetaFlowId())
                .name(flow.getName())
                .status(flow.getStatus())
                .category(flow.getCategory())
                .flowJson(flow.getFlowJson())
                .formId(flow.getFormId())
                .fieldMap(readFieldMap(flow.getFieldMapJson()))
                .publishedAt(flow.getPublishedAt())
                .lastError(flow.getLastError())
                .validationErrors(validationErrors == null ? List.of() : validationErrors)
                .build();
    }

    private FlowResponseView toResponseView(WhatsAppFlowResponse row, WhatsAppFlow flow) {
        return toResponseView(row, flow == null ? null : flow.getName());
    }

    private FlowResponseView toResponseView(WhatsAppFlowResponse row, String flowName) {
        Map<String, Object> answers = Map.of();
        if (row.getAnswersJson() != null && !row.getAnswersJson().isBlank()) {
            try {
                answers = objectMapper.readValue(row.getAnswersJson(),
                        new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) { }
        }
        return FlowResponseView.builder()
                .id(row.getId())
                .flowId(row.getFlowId())
                .flowName(flowName)
                .customerPhone(row.getCustomerPhone())
                .conversationId(row.getConversationId())
                .answers(answers)
                .recordId(row.getRecordId())
                .note(row.getNote())
                .submittedAt(row.getCreatedAt())
                .build();
    }

    private static String cut(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
