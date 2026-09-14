package com.xetax.crm.whatsapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.dto.FieldResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a CRM form into the Flow JSON WhatsApp renders.
 *
 * <p>Writing Flow JSON by hand is where most Flows die: one wrong property
 * name and Meta refuses to publish with an error that names a line number in
 * a file you never wrote. The fields of a CRM form already describe exactly
 * what to ask, so the screens are generated from them.
 *
 * <p>The result is a single-screen Flow that ends in `complete`, which means
 * the answers come back on the webhook as an nfm_reply. That is the shape
 * that needs no public endpoint of ours — a Flow with more than one screen or
 * server-driven data would, and is a separate feature.
 */
@Component
@RequiredArgsConstructor
public class WhatsAppFlowBuilder {

    /** Flow JSON version this builder writes. Meta versions these strictly. */
    private static final String FLOW_VERSION = "7.0";

    private final ObjectMapper objectMapper;

    /** Field types that have no sensible WhatsApp control and are skipped. */
    private static final List<String> UNSUPPORTED =
            List.of("FILE", "IMAGE", "JSON", "PASSWORD", "MULTI_SELECT");

    public String buildFromFields(String title, List<FieldResponse> fields) {
        List<FieldResponse> usable = fields.stream()
                .filter(f -> f.getFieldKey() != null && !f.getFieldKey().isBlank())
                .filter(f -> f.getFieldType() == null
                        || !UNSUPPORTED.contains(f.getFieldType().name()))
                .filter(f -> !Boolean.TRUE.equals(f.getHidden()))
                .limit(8)   // one screen stays readable on a phone
                .toList();
        if (usable.isEmpty()) {
            throw new BadRequestException(
                    "This form has no fields a WhatsApp Flow can ask for");
        }

        List<Map<String, Object>> children = new ArrayList<>();
        children.add(Map.of("type", "TextHeading", "text",
                title == null || title.isBlank() ? "Please fill this in" : title));

        Map<String, Object> payload = new LinkedHashMap<>();
        for (FieldResponse field : usable) {
            children.add(controlFor(field));
            payload.put(field.getFieldKey(), "${form." + field.getFieldKey() + "}");
        }

        children.add(Map.of(
                "type", "Footer",
                "label", "Submit",
                "on-click-action", Map.of(
                        "name", "complete",
                        "payload", payload)));

        Map<String, Object> form = Map.of(
                "type", "Form",
                "name", "form",
                "children", children);

        Map<String, Object> screen = Map.of(
                "id", "FIRST_ENTRY_SCREEN",
                "title", title == null || title.isBlank() ? "Details" : cut(title, 30),
                "terminal", true,
                "data", Map.of(),
                "layout", Map.of(
                        "type", "SingleColumnLayout",
                        "children", List.of(form)));

        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("version", FLOW_VERSION);
        flow.put("screens", List.of(screen));

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(flow);
        } catch (Exception e) {
            throw new BadRequestException("Could not build the Flow: " + e.getMessage());
        }
    }

    /** Maps one CRM field to the WhatsApp control that fits it. */
    private Map<String, Object> controlFor(FieldResponse field) {
        String type = field.getFieldType() == null ? "TEXT" : field.getFieldType().name();
        String label = field.getLabel() == null || field.getLabel().isBlank()
                ? field.getFieldKey() : field.getLabel();
        boolean required = Boolean.TRUE.equals(field.getRequired());

        Map<String, Object> control = new LinkedHashMap<>();
        control.put("name", field.getFieldKey());
        control.put("label", cut(label, 20));
        if (required) control.put("required", true);

        switch (type) {
            case "TEXTAREA" -> {
                control.put("type", "TextArea");
                control.put("max-length", 600);
            }
            case "NUMBER", "DECIMAL" -> {
                control.put("type", "TextInput");
                control.put("input-type", "number");
            }
            case "EMAIL" -> {
                control.put("type", "TextInput");
                control.put("input-type", "email");
            }
            case "PHONE" -> {
                control.put("type", "TextInput");
                control.put("input-type", "phone");
            }
            case "DATE", "DATETIME" -> control.put("type", "DatePicker");
            case "BOOLEAN", "CHECKBOX" -> {
                control.put("type", "OptIn");
                control.put("label", cut(label, 120));
            }
            case "SELECT", "RADIO" -> {
                control.put("type", "RadioButtonsGroup");
                control.put("data-source", optionsOf(field));
            }
            default -> {
                control.put("type", "TextInput");
                control.put("input-type", "text");
            }
        }
        // Meta wants "type" first; rebuilt so the JSON reads the way its docs do.
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("type", control.remove("type"));
        ordered.putAll(control);
        return ordered;
    }

    /**
     * A choice field's options. Meta needs an id and a title per option, and
     * refuses a control with an empty list, so a field with no options
     * configured falls back to a plain text input's worth of nothing — the
     * caller sees the error rather than an unpublishable Flow.
     */
    private List<Map<String, String>> optionsOf(FieldResponse field) {
        List<Map<String, String>> options = new ArrayList<>();
        String raw = field.getOptionsJson();
        if (raw != null && !raw.isBlank()) {
            try {
                for (com.fasterxml.jackson.databind.JsonNode node : objectMapper.readTree(raw)) {
                    String value = node.isTextual() ? node.asText()
                            : node.path("value").asText(node.path("label").asText(""));
                    String label = node.isTextual() ? node.asText()
                            : node.path("label").asText(value);
                    if (value == null || value.isBlank()) continue;
                    options.add(Map.of("id", value, "title", cut(label, 30)));
                    if (options.size() == 20) break;   // Meta's ceiling
                }
            } catch (Exception ignored) {
                // fall through to the error below
            }
        }
        if (options.isEmpty()) {
            throw new BadRequestException(
                    "Field '" + field.getLabel() + "' is a choice field but has no options configured");
        }
        return options;
    }

    private static String cut(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }
}
