package com.xetax.crm.meta;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.service.FormMetaCache;
import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import com.xetax.crm.whatsapp.service.SecretEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** Connect / configure / disconnect a Facebook Page for lead ads. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaConnectService {

    /** Field names Meta uses on its standard lead forms. */
    private static final Map<String, List<String>> STANDARD_HINTS = Map.of(
            "phone", List.of("phone_number", "phone", "mobile", "work_phone"),
            "email", List.of("email", "work_email"),
            "name", List.of("full_name", "name", "first_name"),
            "city", List.of("city"),
            "company", List.of("company_name", "company"));

    private final MetaConnectionRepository connections;
    private final MetaGraphClient graph;
    private final SecretEncryptionService encryption;
    private final MetaAdsProperties adsProperties;
    private final MetaWhatsAppProperties appProperties;
    private final CurrentUserProvider currentUserProvider;
    private final FormRepo formRepo;
    private final FormMetaCache formMetaCache;
    private final ObjectMapper mapper = new ObjectMapper();

    private String owner() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    /** Non-secret values the Connect button needs. */
    public Map<String, Object> signupMeta() {
        return Map.of(
                "appId", appProperties.getAppId() == null ? "" : appProperties.getAppId(),
                "configId", adsProperties.getConfigId() == null ? "" : adsProperties.getConfigId(),
                "graphApiVersion", appProperties.getGraphApiVersion(),
                "configured", !appProperties.getAppId().isBlank() && !adsProperties.getConfigId().isBlank());
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MetaConnection c : connections.findByOwnerUserIdOrderByIdDesc(owner())) out.add(toMap(c));
        return out;
    }

    public record ConnectRequest(String code, String redirectUri) {}

    /**
     * Step 1 of the popup: swap the code for a token and hand back the Pages and
     * ad accounts so the person can choose. Nothing is stored yet except the
     * user token, which the next step needs.
     */
    @Transactional
    public Map<String, Object> beginConnect(ConnectRequest request) {
        if (request == null || request.code() == null || request.code().isBlank()) {
            throw new BadRequestException("Facebook did not return an authorization code — please retry.");
        }
        String own = owner();
        String userToken = graph.exchangeCode(request.code(), request.redirectUri());

        List<MetaGraphClient.PageRef> pages = graph.pages(userToken);
        if (pages.isEmpty()) {
            throw new BadRequestException("No Facebook Page came back. Connect with an account that is an admin of the Page running your ads.");
        }
        List<MetaGraphClient.AdAccountRef> accounts;
        try {
            accounts = graph.adAccounts(userToken);
        } catch (MetaApiException e) {
            accounts = List.of(); // page-only connection is still useful for leads
            log.info("Ad accounts unavailable for {}: {}", own, e.getMessage());
        }

        // Park the token on a draft row keyed by the first page; choosePage moves it.
        MetaConnection draft = connections.findByOwnerUserIdAndPageId(own, pages.get(0).id())
                .orElseGet(() -> MetaConnection.builder().ownerUserId(own).pageId(pages.get(0).id())
                        .status(MetaConnection.DISCONNECTED).createdAt(LocalDateTime.now()).build());
        draft.setUserTokenEncrypted(encryption.encrypt(userToken));
        draft.setUpdatedAt(LocalDateTime.now());
        connections.save(draft);

        Map<String, Object> out = new LinkedHashMap<>();
        // Every value is defaulted — Map.of throws on a null, and Meta does omit
        // fields (a Page with no name set, an ad account without a currency).
        out.put("pages", pages.stream().map(p -> Map.of(
                "id", nullSafe(p.id()),
                "name", nullSafe(p.name()).isBlank() ? nullSafe(p.id()) : nullSafe(p.name()),
                "instagram", nullSafe(p.igUserId()))).toList());
        out.put("adAccounts", accounts.stream().map(a -> Map.of(
                "id", nullSafe(a.id()),
                "name", nullSafe(a.name()).isBlank() ? nullSafe(a.id()) : nullSafe(a.name()),
                "currency", nullSafe(a.currency()))).toList());
        return out;
    }

    public record ChooseRequest(String pageId, String adAccountId, Long formId, Long wonStageId) {}

    /** Step 2: store the chosen Page + ad account and subscribe the Page's leads to us. */
    @Transactional
    public Map<String, Object> choose(ChooseRequest in) {
        String own = owner();
        if (in == null || in.pageId() == null || in.pageId().isBlank()) {
            throw new BadRequestException("Pick the Facebook Page your ads run on");
        }
        MetaConnection draft = connections.findByOwnerUserIdOrderByIdDesc(own).stream()
                .filter(c -> c.getUserTokenEncrypted() != null && !c.getUserTokenEncrypted().isBlank())
                .findFirst()
                .orElseThrow(() -> new BadRequestException("The Facebook session expired — press Connect again."));
        String userToken = encryption.decrypt(draft.getUserTokenEncrypted());

        MetaGraphClient.PageRef page = graph.pages(userToken).stream()
                .filter(p -> p.id().equals(in.pageId())).findFirst()
                .orElseThrow(() -> new BadRequestException("That Page is not available on this Facebook account"));
        if (page.accessToken() == null || page.accessToken().isBlank()) {
            throw new BadRequestException("Facebook did not give a token for this Page. You must be an admin of it.");
        }

        MetaConnection connection = connections.findByOwnerUserIdAndPageId(own, page.id()).orElse(draft);
        connection.setOwnerUserId(own);
        connection.setPageId(page.id());
        connection.setPageName(page.name());
        connection.setIgUserId(page.igUserId());
        connection.setPageTokenEncrypted(encryption.encrypt(page.accessToken()));
        connection.setUserTokenEncrypted(encryption.encrypt(userToken));

        if (in.adAccountId() != null && !in.adAccountId().isBlank()) {
            graph.adAccounts(userToken).stream().filter(a -> a.id().equals(in.adAccountId())).findFirst()
                    .ifPresent(a -> {
                        connection.setAdAccountId(a.id());
                        connection.setAdAccountName(a.name());
                        connection.setCurrency(a.currency());
                    });
        }
        applyForm(connection, own, in.formId(), in.wonStageId());

        try {
            graph.subscribePage(page.id(), page.accessToken());
            connection.setStatus(MetaConnection.CONNECTED);
            connection.setLastError(null);
        } catch (MetaApiException e) {
            connection.setStatus(MetaConnection.ERROR);
            connection.setLastError(e.getMessage());
        }
        if (connection.getCreatedAt() == null) connection.setCreatedAt(LocalDateTime.now());
        connection.setUpdatedAt(LocalDateTime.now());
        MetaConnection saved = connections.save(connection);
        if (MetaConnection.ERROR.equals(saved.getStatus())) {
            throw new BadRequestException("Connected, but Meta refused the lead subscription: " + saved.getLastError());
        }
        return toMap(saved);
    }

    public record SettingsRequest(Long formId, Long wonStageId, Map<String, String> fieldMap, String adAccountId) {}

    @Transactional
    public Map<String, Object> updateSettings(Long id, SettingsRequest in) {
        String own = owner();
        MetaConnection connection = connections.findByIdAndOwnerUserId(id, own)
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found"));
        applyForm(connection, own, in.formId(), in.wonStageId());
        if (in.fieldMap() != null && !in.fieldMap().isEmpty()) {
            connection.setFieldMapJson(write(cleanMap(connection.getTargetFormId(), in.fieldMap())));
        }
        if (in.adAccountId() != null) {
            connection.setAdAccountId(in.adAccountId().isBlank() ? null : in.adAccountId());
        }
        connection.setUpdatedAt(LocalDateTime.now());
        return toMap(connections.save(connection));
    }

    @Transactional
    public void disconnect(Long id) {
        String own = owner();
        MetaConnection connection = connections.findByIdAndOwnerUserId(id, own)
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found"));
        if (connection.getPageTokenEncrypted() != null) {
            try {
                graph.unsubscribePage(connection.getPageId(), encryption.decrypt(connection.getPageTokenEncrypted()));
            } catch (Exception e) {
                log.warn("Unsubscribe on disconnect failed: {}", e.getMessage());
            }
        }
        connections.delete(connection);
    }

    /* ------------------------------------------------------------ helpers */

    private void applyForm(MetaConnection connection, String own, Long formId, Long wonStageId) {
        if (formId == null) return;
        FormEntity form = formRepo.findById(formId).filter(f -> own.equals(f.getOwnerUserId()))
                .orElseThrow(() -> new BadRequestException("That form does not belong to this workspace"));
        boolean formChanged = !form.getId().equals(connection.getTargetFormId());
        connection.setTargetFormId(form.getId());
        if (formChanged || connection.getFieldMapJson() == null || connection.getFieldMapJson().isBlank()) {
            connection.setFieldMapJson(write(defaultMap(form.getId())));
        }
        if (wonStageId != null) {
            boolean valid = formMetaCache.getStages(form.getId()).stream()
                    .map(FormStage::getId).anyMatch(wonStageId::equals);
            connection.setWonStageId(valid ? wonStageId : null);
        }
    }

    /** Meta's standard field names guessed onto this form's fields. */
    public Map<String, String> defaultMap(Long formId) {
        Map<String, String> out = new LinkedHashMap<>();
        List<FormField> fields = formMetaCache.getFields(formId);
        for (Map.Entry<String, List<String>> entry : STANDARD_HINTS.entrySet()) {
            FormField match = switch (entry.getKey()) {
                case "phone" -> pick(fields, f -> f.getFieldType() == FieldType.PHONE
                        || f.getFieldKey().toLowerCase().contains("phone")
                        || f.getFieldKey().toLowerCase().contains("mobile"));
                case "email" -> pick(fields, f -> f.getFieldType() == FieldType.EMAIL
                        || f.getFieldKey().toLowerCase().contains("email"));
                case "name" -> pick(fields, f -> f.getFieldType() == FieldType.TEXT
                        && f.getFieldKey().toLowerCase().contains("name"));
                case "city" -> pick(fields, f -> f.getFieldKey().toLowerCase().contains("city")
                        || f.getFieldKey().toLowerCase().contains("location"));
                default -> pick(fields, f -> f.getFieldKey().toLowerCase().contains("company"));
            };
            if (match == null) continue;
            for (String metaField : entry.getValue()) out.put(metaField, match.getFieldKey());
        }
        return out;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static FormField pick(List<FormField> fields, java.util.function.Predicate<FormField> test) {
        return fields.stream().filter(test).findFirst().orElse(null);
    }

    private Map<String, String> cleanMap(Long formId, Map<String, String> in) {
        if (formId == null) return Map.of();
        Set<String> keys = new HashSet<>();
        for (FormField f : formMetaCache.getFields(formId)) keys.add(f.getFieldKey());
        Map<String, String> out = new LinkedHashMap<>();
        in.forEach((metaField, fieldKey) -> {
            if (metaField != null && fieldKey != null && keys.contains(fieldKey)) out.put(metaField.trim(), fieldKey);
        });
        return out;
    }

    Map<String, String> readMap(MetaConnection connection) {
        if (connection.getFieldMapJson() == null || connection.getFieldMapJson().isBlank()) return Map.of();
        try {
            return mapper.readValue(connection.getFieldMapJson(), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String write(Map<String, String> map) {
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    public Map<String, Object> toMap(MetaConnection c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("pageId", c.getPageId());
        m.put("pageName", c.getPageName());
        m.put("instagram", c.getIgUserId() != null);
        m.put("adAccountId", c.getAdAccountId());
        m.put("adAccountName", c.getAdAccountName());
        m.put("currency", c.getCurrency());
        m.put("targetFormId", c.getTargetFormId());
        formRepo.findById(c.getTargetFormId() == null ? -1L : c.getTargetFormId())
                .ifPresent(f -> m.put("targetFormName", f.getName()));
        m.put("wonStageId", c.getWonStageId());
        m.put("fieldMap", readMap(c));
        m.put("status", c.getStatus());
        m.put("lastError", c.getLastError());
        m.put("lastLeadAt", c.getLastLeadAt());
        m.put("lastInsightSyncAt", c.getLastInsightSyncAt());
        return m;
    }
}
