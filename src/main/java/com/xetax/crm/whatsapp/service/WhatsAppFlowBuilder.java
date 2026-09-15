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
 * name and Meta refuses to publish with an error naming a line in a file you
 * never wrote. The fields of a CRM form already describe exactly what to ask,
 * so the screens are generated from them.
 *
 * <p>A long form becomes several screens rather than one endless one. Each
 * screen hands its answers to the next through the next screen's `data`, and
 * the last screen's `complete` sends the lot back on the webhook. No server of
 * ours is involved: an endpoint is only needed for `data_exchange`, where the
 * server has to compute something mid-Flow, such as branching on an earlier
 * answer or fetching live availability.
 */
@Component
@RequiredArgsConstructor
public class WhatsAppFlowBuilder {

    /** Flow JSON version this builder writes. Meta versions these strictly. */
    private static final String FLOW_VERSION = "7.0";

    /** Questions per screen — enough to be worth a tap, short enough to read. */
    private static final int PER_SCREEN = 6;

    /** Meta's ceiling on screens; a form longer than this cannot be a Flow. */
    private static final int MAX_SCREENS = 8;

    private final ObjectMapper objectMapper;

    /** Field types that have no sensible WhatsApp control. */
    private static final List<String> UNSUPPORTED =
            List.of("FILE", "IMAGE", "JSON", "PASSWORD", "MULTI_SELECT");

    public String buildFromFields(String title, List<FieldResponse> fields) {
        List<FieldResponse> usable = fields.stream()
                .filter(f -> f.getFieldKey() != null && !f.getFieldKey().isBlank())
                .filter(f -> f.getFieldType() == null
                        || !UNSUPPORTED.contains(f.getFieldType().name()))
                .filter(f -> !Boolean.TRUE.equals(f.getHidden()))
                .toList();
        if (usable.isEmpty()) {
            throw new BadRequestException(
                    "This form has no fields a WhatsApp Flow can ask for");
        }

        List<List<FieldResponse>> pages = paginate(usable);
        if (pages.size() > MAX_SCREENS) {
            throw new BadRequestException("This form has " + usable.size()
                    + " fields, which needs " + pages.size() + " screens — WhatsApp allows "
                    + MAX_SCREENS + ". Hide the fields a customer does not need to fill.");
        }

        List<Map<String, Object>> screens = new ArrayList<>();
        Map<String, Object> routing = new LinkedHashMap<>();

        for (int page = 0; page < pages.size(); page++) {
            boolean last = page == pages.size() - 1;
            String id = screenId(page);
            String nextId = last ? null : screenId(page + 1);

            screens.add(screen(id, title, pages, page, nextId));
            routing.put(id, last ? List.of() : List.of(nextId));
        }

        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("version", FLOW_VERSION);
        // A single screen needs no routing model, and Meta prefers it absent.
        if (screens.size() > 1) flow.put("routing_model", routing);
        flow.put("screens", screens);

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(flow);
        } catch (Exception e) {
            throw new BadRequestException("Could not build the Flow: " + e.getMessage());
        }
    }

    /**
     * Splits the questions into screens, but only once there are enough to be
     * worth splitting — a seven-field form reads better as one screen than as
     * six questions and a lonely seventh.
     */
    private static List<List<FieldResponse>> paginate(List<FieldResponse> fields) {
        List<List<FieldResponse>> pages = new ArrayList<>();
        if (fields.size() <= PER_SCREEN + 2) {
            pages.add(fields);
            return pages;
        }
        for (int from = 0; from < fields.size(); from += PER_SCREEN) {
            pages.add(fields.subList(from, Math.min(from + PER_SCREEN, fields.size())));
        }
        return pages;
    }

    /** Meta requires the entry screen to be named this; the rest are ours. */
    private static String screenId(int index) {
        return index == 0 ? "FIRST_ENTRY_SCREEN" : "SCREEN_" + index;
    }

    private Map<String, Object> screen(String id, String title,
                                       List<List<FieldResponse>> pages, int page, String nextId) {
        boolean last = nextId == null;
        boolean multi = pages.size() > 1;
        List<FieldResponse> fields = pages.get(page);

        List<Map<String, Object>> children = new ArrayList<>();
        children.add(Map.of("type", "TextHeading", "text",
                title == null || title.isBlank() ? "Please fill this in" : title));
        if (multi) {
            children.add(Map.of("type", "TextCaption",
                    "text", "Step " + (page + 1) + " of " + pages.size()));
        }

        Map<String, Object> answersHere = new LinkedHashMap<>();
        for (FieldResponse field : fields) {
            children.add(controlFor(field));
            answersHere.put(field.getFieldKey(), "${form." + field.getFieldKey() + "}");
        }

        // Everything answered on earlier screens rides along in this screen's
        // data, so the final payload can carry the whole form.
        Map<String, Object> carried = new LinkedHashMap<>();
        Map<String, Object> dataSchema = new LinkedHashMap<>();
        for (int earlier = 0; earlier < page; earlier++) {
            for (FieldResponse field : pages.get(earlier)) {
                carried.put(field.getFieldKey(), "${data." + field.getFieldKey() + "}");
                dataSchema.put(field.getFieldKey(), Map.of("type", "string", "__example__", ""));
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>(carried);
        payload.putAll(answersHere);

        Map<String, Object> action = new LinkedHashMap<>();
        if (last) {
            action.put("name", "complete");
            action.put("payload", payload);
        } else {
            action.put("name", "navigate");
            action.put("next", Map.of("type", "screen", "name", nextId));
            action.put("payload", payload);
        }

        children.add(Map.of(
                "type", "Footer",
                "label", last ? "Submit" : "Continue",
                "on-click-action", action));

        Map<String, Object> form = Map.of(
                "type", "Form",
                "name", "form",
                "children", children);

        Map<String, Object> screen = new LinkedHashMap<>();
        screen.put("id", id);
        screen.put("title", screenTitle(title, page, pages.size()));
        if (last) screen.put("terminal", true);
        screen.put("data", dataSchema);
        screen.put("layout", Map.of("type", "SingleColumnLayout", "children", List.of(form)));
        return screen;
    }

    private static String screenTitle(String title, int page, int total) {
        String base = title == null || title.isBlank() ? "Details" : title;
        return cut(total > 1 ? base + " " + (page + 1) + "/" + total : base, 30);
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
     * A choice field's options. Meta needs an id and a title per option and
     * refuses a control with an empty list, so a field with none configured is
     * reported rather than shipped as an unpublishable Flow.
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
