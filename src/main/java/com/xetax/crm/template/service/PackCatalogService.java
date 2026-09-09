package com.xetax.crm.template.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.template.model.PackDefinition;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads the built-in vertical packs from {@code classpath:packs/*.json} once
 * at startup and validates any pack (built-in, exported or pasted) before it
 * can be applied. Adding a pack = dropping a JSON file, no code.
 */
@Slf4j
@Service
public class PackCatalogService {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, PackDefinition> builtins = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:packs/*.json");
            List<PackDefinition> list = new ArrayList<>();
            for (Resource r : files) {
                try (var in = r.getInputStream()) {
                    PackDefinition def = mapper.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), PackDefinition.class);
                    validate(def);
                    list.add(def);
                } catch (Exception e) {
                    log.error("Pack {} skipped: {}", r.getFilename(), e.getMessage());
                }
            }
            list.sort(Comparator.comparing(PackDefinition::getName, String.CASE_INSENSITIVE_ORDER));
            for (PackDefinition d : list) builtins.put(d.getKey(), d);
            log.info("Loaded {} built-in vertical packs: {}", builtins.size(), builtins.keySet());
        } catch (Exception e) {
            log.error("Pack catalog failed to load: {}", e.getMessage());
        }
    }

    public Collection<PackDefinition> builtins() { return builtins.values(); }

    public Optional<PackDefinition> builtin(String key) { return Optional.ofNullable(builtins.get(key)); }

    public PackDefinition parse(String json) {
        try {
            PackDefinition def = mapper.readValue(json, PackDefinition.class);
            validate(def);
            return def;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Not a valid pack JSON: " + e.getMessage());
        }
    }

    public String write(PackDefinition def) {
        try { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(def); }
        catch (Exception e) { throw new BadRequestException("Pack could not be serialized: " + e.getMessage()); }
    }

    /** Structural checks — everything a pack references must exist inside the pack. */
    public void validate(PackDefinition def) {
        if (def == null) throw new BadRequestException("Empty pack");
        if (def.getKey() == null || !def.getKey().matches("[a-z0-9_]{2,80}")) {
            throw new BadRequestException("Pack key must be 2-80 chars of a-z, 0-9 or _");
        }
        if (def.getName() == null || def.getName().isBlank()) throw new BadRequestException("Pack needs a name");
        if (def.getFields() == null || def.getFields().isEmpty()) throw new BadRequestException("Pack needs at least one field");
        if (def.getStages() == null || def.getStages().isEmpty()) throw new BadRequestException("Pack needs at least one stage");
        if (def.getIcon() == null || def.getIcon().isBlank()) def.setIcon("bi-table");
        if (def.getColor() == null || def.getColor().isBlank()) def.setColor("#4f46e5");

        Set<String> keys = new HashSet<>();
        for (PackDefinition.Field f : def.getFields()) {
            if (f.getFieldKey() == null || !f.getFieldKey().matches("[a-zA-Z0-9_]{1,64}")) throw new BadRequestException("Bad field key: " + f.getFieldKey());
            if (!keys.add(f.getFieldKey())) throw new BadRequestException("Duplicate field key: " + f.getFieldKey());
            if (f.getLabel() == null || f.getLabel().isBlank()) f.setLabel(f.getFieldKey());
            try { FieldType.valueOf(f.getFieldType()); }
            catch (Exception e) { throw new BadRequestException("Unknown field type '" + f.getFieldType() + "' on " + f.getFieldKey()); }
        }
        Set<String> codes = new HashSet<>();
        Set<Integer> seqs = new HashSet<>();
        int defaults = 0;
        for (PackDefinition.Stage s : def.getStages()) {
            if (s.getCode() == null || s.getCode().isBlank()) throw new BadRequestException("Every stage needs a code");
            if (!codes.add(s.getCode())) throw new BadRequestException("Duplicate stage code: " + s.getCode());
            if (!seqs.add(s.getSequence())) throw new BadRequestException("Duplicate stage sequence: " + s.getSequence());
            if (s.getName() == null || s.getName().isBlank()) s.setName(s.getCode());
            if (s.isDefault()) defaults++;
        }
        if (defaults == 0) def.getStages().get(0).setDefault(true);
        if (defaults > 1) throw new BadRequestException("Only one stage can be the default");

        if (def.getAutomations() != null) for (PackDefinition.Automation a : def.getAutomations()) {
            if (a.getTriggerStageCode() != null && !codes.contains(a.getTriggerStageCode())) throw new BadRequestException("Automation '" + a.getName() + "' references unknown stage " + a.getTriggerStageCode());
            if (a.getActionFieldKey() != null && !keys.contains(a.getActionFieldKey())) throw new BadRequestException("Automation '" + a.getName() + "' references unknown field " + a.getActionFieldKey());
            if (a.getActionStageCode() != null && !codes.contains(a.getActionStageCode())) throw new BadRequestException("Automation '" + a.getName() + "' references unknown stage " + a.getActionStageCode());
        }
        if (def.getWhatsappTemplates() != null) for (PackDefinition.WaTemplate t : def.getWhatsappTemplates()) {
            if (t.getName() == null || !t.getName().matches("[a-z0-9_]{1,120}")) throw new BadRequestException("WhatsApp template names use lowercase letters, digits and _ only: " + t.getName());
            if (t.getBodyText() == null || t.getBodyText().isBlank()) throw new BadRequestException("WhatsApp template '" + t.getName() + "' needs a body");
            if (t.getCategory() == null || !List.of("MARKETING", "UTILITY").contains(t.getCategory().toUpperCase())) t.setCategory("UTILITY");
            t.setCategory(t.getCategory().toUpperCase());
            if (t.getLanguage() == null || t.getLanguage().isBlank()) t.setLanguage("en");
        }
        if (def.getAgent() != null) {
            if (def.getAgent().getName() == null || def.getAgent().getName().isBlank()) def.getAgent().setName(def.getName() + " Assistant");
            if (def.getAgent().getStageHints() != null) for (PackDefinition.StageHint h : def.getAgent().getStageHints()) {
                if (!codes.contains(h.getStageCode())) throw new BadRequestException("Agent hint references unknown stage " + h.getStageCode());
            }
        }
        if (def.getPlaybook() != null) {
            if (def.getPlaybook().getQualificationKeys() != null) for (String k : def.getPlaybook().getQualificationKeys()) {
                if (!keys.contains(k)) throw new BadRequestException("Playbook qualification key unknown: " + k);
            }
            if (def.getPlaybook().getRules() != null) for (PackDefinition.Rule r : def.getPlaybook().getRules()) {
                if (r.getName() == null || r.getName().isBlank()) throw new BadRequestException("Every playbook rule needs a name");
                if (r.getStageCodes() != null) for (String c : r.getStageCodes()) if (!codes.contains(c)) throw new BadRequestException("Rule '" + r.getName() + "' references unknown stage " + c);
                if (r.getTargetStageCode() != null && !codes.contains(r.getTargetStageCode())) throw new BadRequestException("Rule '" + r.getName() + "' targets unknown stage " + r.getTargetStageCode());
            }
        }
    }

    /** Short summary for the gallery card. */
    public static Map<String, Object> includes(PackDefinition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fields", d.getFields() == null ? 0 : d.getFields().size());
        m.put("stages", d.getStages() == null ? 0 : d.getStages().size());
        m.put("automations", d.getAutomations() == null ? 0 : d.getAutomations().size());
        m.put("whatsappTemplates", d.getWhatsappTemplates() == null ? 0 : d.getWhatsappTemplates().size());
        m.put("agent", d.getAgent() != null);
        m.put("playbook", d.getPlaybook() != null && d.getPlaybook().getRules() != null && !d.getPlaybook().getRules().isEmpty());
        m.put("playbookRules", d.getPlaybook() == null || d.getPlaybook().getRules() == null ? 0 : d.getPlaybook().getRules().size());
        return m;
    }
}
