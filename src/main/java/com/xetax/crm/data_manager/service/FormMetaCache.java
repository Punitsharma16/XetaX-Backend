package com.xetax.crm.data_manager.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.repository.FormFieldRepo;
import com.xetax.crm.data_manager.repository.StageRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Redis cache for a form's field and stage metadata.
 *
 * <p>This is the hottest read path in the CRM: EVERY record create/update/
 * search loads the form's fields for validation, and record creation loads
 * the stages for the default stage. Fields/stages change rarely; records are
 * written constantly — so the lists are cached as JSON for {@link #TTL} and
 * evicted whenever a field/stage of that form is created, updated or
 * deleted.
 *
 * <p>Redis being down never breaks a request — every operation falls back
 * to the repository.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormMetaCache {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String FIELDS_PREFIX = "xetax:form:fields:";
    private static final String STAGES_PREFIX = "xetax:form:stages:";

    private final FormFieldRepo formFieldRepo;

    private final StageRepo stageRepo;

    private final StringRedisTemplate redis;

    private final ObjectMapper objectMapper;

    public List<FormField> getFields(Long formId) {
        String key = FIELDS_PREFIX + formId;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<FormField>>() {
                });
            }
        } catch (Exception e) {
            log.debug("Field cache read skipped: {}", e.getMessage());
        }
        List<FormField> fields = formFieldRepo.findByFormIdOrderByDisplayOrder(formId);
        put(key, fields);
        return fields;
    }

    public List<FormStage> getStages(Long formId) {
        String key = STAGES_PREFIX + formId;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<FormStage>>() {
                });
            }
        } catch (Exception e) {
            log.debug("Stage cache read skipped: {}", e.getMessage());
        }
        List<FormStage> stages = stageRepo.findByFormIdOrderBySequence(formId);
        put(key, stages);
        return stages;
    }

    public void evictFields(Long formId) {
        evict(FIELDS_PREFIX + formId);
    }

    public void evictStages(Long formId) {
        evict(STAGES_PREFIX + formId);
    }

    private void put(String key, Object value) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), TTL);
        } catch (Exception e) {
            log.debug("Form-meta cache write skipped: {}", e.getMessage());
        }
    }

    private void evict(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.debug("Form-meta cache evict skipped: {}", e.getMessage());
        }
    }
}
