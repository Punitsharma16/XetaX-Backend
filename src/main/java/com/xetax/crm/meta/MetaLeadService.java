package com.xetax.crm.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.xetax.crm.activity.RecordActivityService;
import com.xetax.crm.automation.engine.AutomationEngine;
import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import com.xetax.crm.data_manager.service.FormMetaCache;
import com.xetax.crm.notification.NotificationService;
import com.xetax.crm.whatsapp.service.SecretEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * Turns a Facebook / Instagram lead-form submission into a CRM record.
 *
 * Runs on a webhook thread, so it never calls the UI services (no security
 * context there) — it writes through the repositories the same way the chat
 * bot does, then lets automations and the sales playbook take over.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaLeadService {

    private final MetaConnectionRepository connections;
    private final MetaLeadAttributionRepository attributions;
    private final MetaConnectService connectService;
    private final MetaGraphClient graph;
    private final SecretEncryptionService encryption;
    private final FormRepo formRepo;
    private final FormMetaCache formMetaCache;
    private final RecordRepo recordRepo;
    private final MongoTemplate mongoTemplate;
    private final AutomationEngine automationEngine;
    private final RecordActivityService activityService;
    private final NotificationService notificationService;

    /** One leadgen entry from the page webhook. */
    @Async("botExecutor")
    public void onLeadgen(String pageId, String leadgenId, String metaFormId, String adId, String createdTime) {
        try {
            handle(pageId, leadgenId);
        } catch (Exception e) {
            log.warn("Meta lead {} on page {} failed: {}", leadgenId, pageId, e.getMessage());
        }
    }

    /**
     * Deliveries of the same lead that are already being processed. Meta resends
     * a webhook until it sees a 200, and two copies can land at once — without
     * this both threads pass the "seen it?" check and the customer gets two
     * records. The unique index on leadgen_id is the last line of defence.
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    @Transactional
    public void handle(String pageId, String leadgenId) {
        if (leadgenId == null || leadgenId.isBlank()) return;
        if (attributions.existsByLeadgenId(leadgenId)) return; // already ingested
        if (!inFlight.add(leadgenId)) return;                  // a twin delivery has it
        try {
            ingest(pageId, leadgenId);
        } finally {
            inFlight.remove(leadgenId);
        }
    }

    private void ingest(String pageId, String leadgenId) {

        MetaConnection connection = connections.findFirstByPageIdAndStatus(pageId, MetaConnection.CONNECTED).orElse(null);
        if (connection == null) {
            log.info("Lead for page {} ignored — no connected workspace", pageId);
            return;
        }
        if (connection.getTargetFormId() == null) {
            log.warn("Lead for page {} dropped — no CRM form chosen yet", pageId);
            return;
        }
        FormEntity form = formRepo.findById(connection.getTargetFormId()).orElse(null);
        if (form == null) return;

        String pageToken = encryption.decrypt(connection.getPageTokenEncrypted());
        JsonNode lead = graph.lead(leadgenId, pageToken);

        Map<String, String> answers = new LinkedHashMap<>();
        for (JsonNode field : lead.path("field_data")) {
            String name = field.path("name").asText("");
            JsonNode values = field.path("values");
            if (name.isBlank() || !values.isArray() || values.isEmpty()) continue;
            answers.put(name.toLowerCase(), values.get(0).asText(""));
        }

        Map<String, String> fieldMap = connectService.readMap(connection);
        if (fieldMap.isEmpty()) fieldMap = connectService.defaultMap(form.getId());
        List<FormField> fields = formMetaCache.getFields(form.getId());
        Map<String, FormField> byKey = new HashMap<>();
        for (FormField f : fields) byKey.put(f.getFieldKey(), f);

        Map<String, Object> data = new LinkedHashMap<>();
        StringBuilder unmapped = new StringBuilder();
        for (Map.Entry<String, String> answer : answers.entrySet()) {
            String fieldKey = fieldMap.get(answer.getKey());
            FormField field = fieldKey == null ? null : byKey.get(fieldKey);
            if (field == null) {
                unmapped.append(answer.getKey().replace('_', ' ')).append(": ").append(answer.getValue()).append('\n');
                continue;
            }
            data.put(field.getFieldKey(), coerce(field, answer.getValue()));
        }
        // Anything the form asked that the CRM has no field for still reaches the
        // team — dropped answers are worse than a slightly long note.
        if (!unmapped.isEmpty()) {
            FormField notes = fields.stream()
                    .filter(f -> f.getFieldType() == FieldType.TEXTAREA && !data.containsKey(f.getFieldKey()))
                    .findFirst().orElse(null);
            if (notes != null) data.put(notes.getFieldKey(), unmapped.toString().trim());
        }
        FormField source = fields.stream()
                .filter(f -> f.getFieldKey().toLowerCase().contains("source") && !data.containsKey(f.getFieldKey()))
                .findFirst().orElse(null);
        String platform = lead.path("platform").asText("facebook");
        if (source != null) data.put(source.getFieldKey(), "instagram".equalsIgnoreCase(platform) ? "Instagram Ad" : "Facebook Ad");

        String phone = firstValue(data, fields, true);
        RecordDocument record = phone == null ? null : findByPhone(form.getId(), fields, phone);
        boolean created = false;
        if (record == null) {
            FormStage first = formMetaCache.getStages(form.getId()).stream()
                    .filter(s -> Boolean.TRUE.equals(s.getIsDefault())).findFirst().orElse(null);
            if (first == null) {
                log.warn("Lead {} dropped — form {} has no default stage", leadgenId, form.getId());
                return;
            }
            record = recordRepo.save(RecordDocument.builder()
                    .formId(form.getId()).stageId(first.getId()).data(data)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
            created = true;
        } else {
            Map<String, Object> merged = record.getData() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(record.getData());
            data.forEach((k, v) -> {
                Object existing = merged.get(k);
                if (existing == null || String.valueOf(existing).isBlank()) merged.put(k, v);
            });
            record.setData(merged);
            record.setUpdatedAt(LocalDateTime.now());
            record = recordRepo.save(record);
        }

        // The campaign's NAME is not on the lead payload; the nightly insights
        // sync fills it in, and the report falls back to that.
        attributions.save(MetaLeadAttribution.builder()
                .ownerUserId(connection.getOwnerUserId()).leadgenId(leadgenId).recordId(record.getId())
                .pageId(pageId).metaFormId(lead.path("form_id").asText(null))
                .metaFormName(formNameOf(lead.path("form_id").asText(null), pageToken))
                .campaignId(lead.path("campaign_id").asText(null))
                .adsetId(lead.path("adset_id").asText(null)).adId(lead.path("ad_id").asText(null))
                .platform(platform).createdAt(LocalDateTime.now()).build());

        connection.setLastLeadAt(LocalDateTime.now());
        connections.save(connection);

        String label = "instagram".equalsIgnoreCase(platform) ? "Instagram ad" : "Facebook ad";
        activityService.log(record.getId(), connection.getOwnerUserId(), created ? "CREATED" : "UPDATED",
                (created ? "Lead from a " : "New enquiry on the same number from a ") + label);
        if (created) {
            try {
                automationEngine.execute(AutomationTrigger.RECORD_CREATED, form, record);
            } catch (Exception e) {
                log.warn("RECORD_CREATED automation after a Meta lead failed: {}", e.getMessage());
            }
        }
        notificationService.push(connection.getOwnerUserId(), connection.getOwnerUserId(), "RECORD_CREATED",
                "New lead from your " + label,
                String.valueOf(data.values().stream().findFirst().orElse("")),
                "/app/records/" + form.getSlug() + "/" + record.getId());
    }

    /* ------------------------------------------------------------ helpers */

    private String formNameOf(String metaFormId, String pageToken) {
        if (metaFormId == null || metaFormId.isBlank()) return null;
        try {
            return graph.formName(metaFormId, pageToken).path("name").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstValue(Map<String, Object> data, List<FormField> fields, boolean phone) {
        for (FormField f : fields) {
            String key = f.getFieldKey().toLowerCase();
            boolean match = phone
                    ? f.getFieldType() == FieldType.PHONE || key.contains("phone") || key.contains("mobile")
                    : f.getFieldType() == FieldType.EMAIL || key.contains("email");
            Object v = data.get(f.getFieldKey());
            if (match && v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return null;
    }

    /** Same matching the chat bot uses, so one customer never becomes two records. */
    private RecordDocument findByPhone(Long formId, List<FormField> fields, String phone) {
        FormField field = fields.stream().filter(f -> f.getFieldType() == FieldType.PHONE
                || f.getFieldKey().toLowerCase().contains("phone")
                || f.getFieldKey().toLowerCase().contains("mobile")).findFirst().orElse(null);
        if (field == null) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        List<Criteria> or = new ArrayList<>();
        or.add(Criteria.where("data." + field.getFieldKey()).is(phone.trim()));
        if (!digits.isEmpty()) {
            or.add(Criteria.where("data." + field.getFieldKey()).is(digits));
            if (digits.length() > 10) {
                or.add(Criteria.where("data." + field.getFieldKey()).is(digits.substring(digits.length() - 10)));
            }
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("formId").is(formId),
                new Criteria().orOperator(or.toArray(new Criteria[0]))));
        return mongoTemplate.findOne(query, RecordDocument.class);
    }

    private static Object coerce(FormField field, String value) {
        if (field.getFieldType() == FieldType.NUMBER) {
            try {
                return Long.parseLong(value.replaceAll("[^0-9-]", ""));
            } catch (Exception e) {
                return value;
            }
        }
        if (field.getFieldType() == FieldType.PHONE) return value.replaceAll("[^0-9+]", "");
        return value.trim();
    }
}
