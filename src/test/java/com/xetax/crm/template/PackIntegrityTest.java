package com.xetax.crm.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.template.model.PackDefinition;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every ready-made pack in the panel's gallery, checked the way installing it
 * would. The installer skips a broken automation with a log line and carries
 * on, so a pack that promises four automations can quietly deliver two — the
 * owner only finds out when the message they were counting on never goes out.
 */
class PackIntegrityTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");
    private static final Pattern WA_SLOT = Pattern.compile("\\{\\{(\\d+)}}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Placeholders the resolver fills from elsewhere than the form's fields. */
    private static final Set<String> BUILT_IN_PLACEHOLDERS = Set.of(
            "record_id", "form_name", "stage", "company", "amount", "date", "today");

    private static List<Path> packFiles() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/packs"))) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @TestFactory
    Stream<DynamicTest> everyPackInstallsWholeAndWithoutDanglingReferences() throws IOException {
        List<Path> packs = packFiles();
        assertTrue(packs.size() >= 9, "expected the shipped packs, found " + packs.size());

        return packs.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
            PackDefinition pack;
            try (InputStream in = Files.newInputStream(path)) {
                pack = MAPPER.readValue(in, PackDefinition.class);
            }
            List<String> problems = check(pack);
            assertTrue(problems.isEmpty(), path.getFileName() + ":\n  " + String.join("\n  ", problems));
        }));
    }

    private List<String> check(PackDefinition pack) {
        List<String> problems = new ArrayList<>();

        Set<String> fieldKeys = new HashSet<>();
        for (PackDefinition.Field field : pack.getFields()) {
            if (!fieldKeys.add(field.getFieldKey())) {
                problems.add("two fields share the key " + field.getFieldKey());
            }
            try {
                FieldType type = FieldType.valueOf(field.getFieldType());
                if (type == FieldType.SELECT && (field.getOptionsJson() == null || field.getOptionsJson().isBlank())) {
                    problems.add("select field " + field.getFieldKey() + " has no options");
                }
            } catch (IllegalArgumentException e) {
                problems.add("field " + field.getFieldKey() + " has unknown type " + field.getFieldType());
            }
        }

        Set<String> stageCodes = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        int defaults = 0;
        for (PackDefinition.Stage stage : pack.getStages()) {
            if (!stageCodes.add(stage.getCode())) problems.add("two stages share the code " + stage.getCode());
            if (!sequences.add(stage.getSequence())) {
                problems.add("two stages share position " + stage.getSequence());
            }
            if (stage.isDefault()) defaults++;
        }
        if (!pack.getStages().isEmpty() && defaults != 1) {
            problems.add("a pipeline needs exactly one starting stage, this one has " + defaults);
        }

        Set<String> templateNames = new HashSet<>();
        for (PackDefinition.WaTemplate template : pack.getWhatsappTemplates()) {
            templateNames.add(template.getName());
            int slots = 0;
            Matcher matcher = WA_SLOT.matcher(template.getBodyText() == null ? "" : template.getBodyText());
            while (matcher.find()) slots = Math.max(slots, Integer.parseInt(matcher.group(1)));
            int examples = template.getExampleParams() == null ? 0 : template.getExampleParams().size();
            if (slots != examples) {
                problems.add("template " + template.getName() + " has " + slots
                        + " variable(s) but " + examples + " example value(s) — Meta rejects that");
            }
        }

        for (PackDefinition.Automation automation : pack.getAutomations()) {
            String where = "automation '" + automation.getName() + "'";
            try {
                AutomationTrigger.valueOf(automation.getTrigger());
            } catch (IllegalArgumentException e) {
                problems.add(where + " has unknown trigger " + automation.getTrigger());
            }
            // The installer drops these on the floor with a log line, so the
            // gallery counted automations the owner never received.
            if (AutomationTrigger.STATUS_CHANGED.name().equals(automation.getTrigger())) {
                problems.add(where + " fires on a record status, which this CRM no longer has — "
                        + "the installer skips it, so the pack promises an automation nobody gets");
            }
            AutomationActionType action = null;
            try {
                action = AutomationActionType.valueOf(automation.getActionType());
            } catch (IllegalArgumentException e) {
                problems.add(where + " has unknown action " + automation.getActionType());
            }
            if (automation.getTriggerStageCode() != null && !stageCodes.contains(automation.getTriggerStageCode())) {
                problems.add(where + " waits for stage " + automation.getTriggerStageCode() + ", which this pack never creates");
            }
            if (action == AutomationActionType.CHANGE_STAGE && automation.getActionStageCode() != null
                    && !stageCodes.contains(automation.getActionStageCode())) {
                problems.add(where + " moves records to stage " + automation.getActionStageCode() + ", which this pack never creates");
            }
            if (automation.getActionFieldKey() != null && !fieldKeys.contains(automation.getActionFieldKey())) {
                problems.add(where + " uses field " + automation.getActionFieldKey() + ", which this pack never creates");
            }
            problems.addAll(unknownPlaceholders(where + " message", automation.getEmailMessage(), fieldKeys));
            problems.addAll(unknownPlaceholders(where + " subject", automation.getEmailSubject(), fieldKeys));
        }

        if (pack.getAgent() != null && pack.getAgent().getStageHints() != null) {
            for (PackDefinition.StageHint hint : pack.getAgent().getStageHints()) {
                if (!stageCodes.contains(hint.getStageCode())) {
                    problems.add("the agent hints at stage " + hint.getStageCode() + ", which this pack never creates");
                }
            }
        }

        if (pack.getPlaybook() != null) {
            for (String key : pack.getPlaybook().getQualificationKeys()) {
                if (!fieldKeys.contains(key)) {
                    problems.add("the playbook qualifies on field " + key + ", which this pack never creates");
                }
            }
            for (PackDefinition.Rule rule : pack.getPlaybook().getRules()) {
                String where = "playbook rule '" + rule.getName() + "'";
                for (String code : rule.getStageCodes()) {
                    if (!stageCodes.contains(code)) {
                        problems.add(where + " runs on stage " + code + ", which this pack never creates");
                    }
                }
                if (rule.getTargetStageCode() != null && !stageCodes.contains(rule.getTargetStageCode())) {
                    problems.add(where + " moves records to stage " + rule.getTargetStageCode() + ", which this pack never creates");
                }
                if (rule.getTemplateName() != null && !templateNames.contains(rule.getTemplateName())) {
                    problems.add(where + " sends template " + rule.getTemplateName() + ", which this pack never creates");
                }
                for (String param : rule.getTemplateParams()) {
                    if (!fieldKeys.contains(param) && !BUILT_IN_PLACEHOLDERS.contains(param)) {
                        problems.add(where + " fills a template with field " + param + ", which this pack never creates");
                    }
                }
                for (PackDefinition.Condition condition : rule.getConditions()) {
                    if (!fieldKeys.contains(condition.getFieldKey())) {
                        problems.add(where + " tests field " + condition.getFieldKey() + ", which this pack never creates");
                    }
                }
                problems.addAll(unknownPlaceholders(where + " message", rule.getMessage(), fieldKeys));
            }
        }

        return problems;
    }

    private List<String> unknownPlaceholders(String where, String text, Set<String> fieldKeys) {
        if (text == null || text.isBlank()) return List.of();
        List<String> problems = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!fieldKeys.contains(key) && !BUILT_IN_PLACEHOLDERS.contains(key)) {
                problems.add(where + " writes {" + key + "}, which this pack never creates");
            }
        }
        return problems;
    }

    @TestFactory
    Stream<DynamicTest> everyPackNamesItselfForTheGallery() throws IOException {
        return packFiles().stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
            PackDefinition pack = MAPPER.readValue(Files.readString(path), PackDefinition.class);
            assertTrue(pack.getKey() != null && !pack.getKey().isBlank(), "no key");
            assertTrue(pack.getName() != null && !pack.getName().isBlank(), "no name");
            assertTrue(pack.getTagline() != null && !pack.getTagline().isBlank(), "no tagline");
            assertEquals(path.getFileName().toString().replace(".json", ""), pack.getKey(),
                    "the file name and the pack key must match — the gallery looks packs up by key");
        }));
    }
}
