package com.xetax.crm.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.whatsapp.dto.TemplateVariables;
import com.xetax.crm.whatsapp.entity.WhatsAppTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One place that knows every variable a template has, and turns the values
 * for them into the components Meta expects on a send.
 *
 * <p>It reads the template as saved — Meta's own format, whether the template
 * was created in the panel or synced from Business Manager — so a text header
 * variable, a URL button, a carousel card, a coupon or an OTP code are all
 * found the same way. The number of values must match the number of
 * variables exactly; a mismatch is refused here with a sentence that names the
 * spot, instead of reaching Meta and failing with a generic error.
 */
@Component
@RequiredArgsConstructor
public class WhatsAppTemplateVariables {

    private static final Pattern VAR = Pattern.compile("\\{\\{(\\d+)}}");
    private static final List<String> MEDIA = List.of("IMAGE", "VIDEO", "DOCUMENT");

    private final ObjectMapper objectMapper;

    public record ButtonSlot(int index, String type, boolean needsValue) {}

    public record CardSlot(int index, String headerFormat, int bodyVars, List<ButtonSlot> buttons) {}

    public record Slots(String headerFormat, int headerVars, int bodyVars, List<ButtonSlot> buttons,
                        List<CardSlot> cards, boolean authentication) {}

    /* --------------------------------------------------------------- reading */

    public Slots slotsOf(String componentsJson, String category) {
        String headerFormat = null;
        int headerVars = 0;
        int bodyVars = 0;
        List<ButtonSlot> buttons = new ArrayList<>();
        List<CardSlot> cards = new ArrayList<>();
        boolean authentication = "AUTHENTICATION".equalsIgnoreCase(category);

        for (JsonNode component : read(componentsJson)) {
            switch (component.path("type").asText("").toUpperCase()) {
                case "HEADER" -> {
                    headerFormat = component.path("format").asText("TEXT").toUpperCase();
                    if ("TEXT".equals(headerFormat)) headerVars = maxVar(component.path("text").asText(""));
                }
                case "BODY" -> bodyVars = maxVar(component.path("text").asText(""));
                case "BUTTONS" -> buttons = buttonSlots(component.path("buttons"));
                case "CAROUSEL" -> {
                    int index = 0;
                    for (JsonNode card : component.path("cards")) cards.add(cardSlot(index++, card));
                }
                default -> { }
            }
        }
        // An OTP body always carries the code, even when Meta's structure
        // (security recommendation, no custom text) shows no {{1}} in it.
        if (authentication && bodyVars == 0) bodyVars = 1;
        return new Slots(headerFormat, headerVars, bodyVars, buttons, cards, authentication);
    }

    private List<ButtonSlot> buttonSlots(JsonNode buttons) {
        List<ButtonSlot> out = new ArrayList<>();
        int index = 0;
        for (JsonNode button : buttons) {
            String type = button.path("type").asText("").toUpperCase();
            boolean needsValue = ("URL".equals(type) && VAR.matcher(button.path("url").asText("")).find())
                    || "OTP".equals(type) || "COPY_CODE".equals(type);
            out.add(new ButtonSlot(index++, type, needsValue));
        }
        return out;
    }

    private CardSlot cardSlot(int index, JsonNode card) {
        String headerFormat = "IMAGE";
        int bodyVars = 0;
        List<ButtonSlot> buttons = List.of();
        for (JsonNode component : card.path("components")) {
            switch (component.path("type").asText("").toUpperCase()) {
                case "HEADER" -> headerFormat = component.path("format").asText("IMAGE").toUpperCase();
                case "BODY" -> bodyVars = maxVar(component.path("text").asText(""));
                case "BUTTONS" -> buttons = buttonSlots(component.path("buttons"));
                default -> { }
            }
        }
        return new CardSlot(index, headerFormat, bodyVars, buttons);
    }

    /* -------------------------------------------------------------- building */

    /** Meta send components for this template and these values, or null when it has none. */
    public String build(WhatsAppTemplate template, TemplateVariables given) {
        TemplateVariables v = given == null ? new TemplateVariables() : given;
        Slots slots = slotsOf(template.getComponentsJson(), template.getCategory());
        String name = template.getName();
        List<Map<String, Object>> out = new ArrayList<>();

        // header
        if ("TEXT".equals(slots.headerFormat())) {
            requireCount(name, "header", slots.headerVars(), v.getHeader());
            if (slots.headerVars() > 0) out.add(component("header", texts(v.getHeader())));
        } else {
            requireCount(name, "header", 0, v.getHeader());
            if (slots.headerFormat() != null && MEDIA.contains(slots.headerFormat())) {
                String link = firstNonBlank(v.getHeaderMediaUrl(), template.getHeaderMediaUrl());
                if (link == null) {
                    throw bad("Template '" + name + "' has a " + slots.headerFormat().toLowerCase()
                            + " header — give the link of the file to send");
                }
                out.add(component("header", List.of(media(slots.headerFormat(), link))));
            }
        }

        // body — for an OTP template the code may arrive as the button value instead
        List<String> body = v.getBody();
        if (slots.authentication() && (body == null || body.isEmpty())) {
            String code = v.getButtons() == null ? null
                    : v.getButtons().values().stream().filter(s -> s != null && !s.isBlank()).findFirst().orElse(null);
            if (code != null) body = List.of(code);
        }
        requireCount(name, "body", slots.bodyVars(), body);
        if (slots.bodyVars() > 0) out.add(component("body", texts(body)));

        // buttons
        out.addAll(buttonParams(name, "", slots.buttons(), v.getButtons(),
                slots.authentication() ? body : null, false));

        // carousel
        List<TemplateVariables.Card> givenCards = v.getCards() == null ? List.of() : v.getCards();
        if (slots.cards().isEmpty()) {
            if (!givenCards.isEmpty()) throw bad("Template '" + name + "' has no carousel cards");
        } else {
            if (givenCards.size() > slots.cards().size()) {
                throw bad("Template '" + name + "' has " + slots.cards().size()
                        + " carousel cards, got values for " + givenCards.size());
            }
            List<String> savedLinks = readLinks(template.getCardMediaJson());
            List<Map<String, Object>> cards = new ArrayList<>();
            for (CardSlot card : slots.cards()) {
                TemplateVariables.Card values = card.index() < givenCards.size()
                        ? givenCards.get(card.index()) : new TemplateVariables.Card();
                String where = "card " + (card.index() + 1);

                List<Map<String, Object>> parts = new ArrayList<>();
                String link = firstNonBlank(values.getHeaderMediaUrl(),
                        card.index() < savedLinks.size() ? savedLinks.get(card.index()) : null);
                if (link == null) {
                    throw bad("Template '" + name + "' " + where + " needs the link of its "
                            + card.headerFormat().toLowerCase());
                }
                parts.add(component("header", List.of(media(card.headerFormat(), link))));
                requireCount(name, where + " body", card.bodyVars(), values.getBody());
                if (card.bodyVars() > 0) parts.add(component("body", texts(values.getBody())));
                parts.addAll(buttonParams(name, where + " ", card.buttons(), values.getButtons(), null, true));

                Map<String, Object> node = new LinkedHashMap<>();
                node.put("card_index", card.index());
                node.put("components", parts);
                cards.add(node);
            }
            Map<String, Object> carousel = new LinkedHashMap<>();
            carousel.put("type", "carousel");
            carousel.put("cards", cards);
            out.add(carousel);
        }

        if (out.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            throw bad("Could not prepare template '" + name + "': " + e.getMessage());
        }
    }

    /** Every value passed through {@code fn} — used to resolve {fieldKey} placeholders or field keys. */
    public TemplateVariables map(TemplateVariables source, UnaryOperator<String> fn) {
        TemplateVariables out = new TemplateVariables();
        if (source == null) return out;
        out.setHeader(mapList(source.getHeader(), fn));
        out.setHeaderMediaUrl(source.getHeaderMediaUrl() == null ? null : fn.apply(source.getHeaderMediaUrl()));
        out.setBody(mapList(source.getBody(), fn));
        out.setButtons(mapButtons(source.getButtons(), fn));
        List<TemplateVariables.Card> cards = new ArrayList<>();
        if (source.getCards() != null) {
            for (TemplateVariables.Card card : source.getCards()) {
                TemplateVariables.Card copy = new TemplateVariables.Card();
                copy.setHeaderMediaUrl(card.getHeaderMediaUrl() == null ? null : fn.apply(card.getHeaderMediaUrl()));
                copy.setBody(mapList(card.getBody(), fn));
                copy.setButtons(mapButtons(card.getButtons(), fn));
                cards.add(copy);
            }
        }
        out.setCards(cards);
        return out;
    }

    /* --------------------------------------------------------------- helpers */

    private List<Map<String, Object>> buttonParams(String name, String where, List<ButtonSlot> slots,
                                                   Map<Integer, String> given, List<String> otpBody,
                                                   boolean inCarousel) {
        Map<Integer, String> values = given == null ? Map.of() : given;
        for (Integer key : values.keySet()) {
            boolean takesValue = slots.stream().anyMatch(b -> b.index() == key && b.needsValue());
            if (!takesValue) {
                throw bad("Template '" + name + "' " + where + "button " + (key + 1) + " takes no value");
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ButtonSlot button : slots) {
            String index = String.valueOf(button.index());
            if (button.needsValue()) {
                String value = values.get(button.index());
                if ((value == null || value.isBlank()) && "OTP".equals(button.type())
                        && otpBody != null && !otpBody.isEmpty()) {
                    value = otpBody.get(0);
                }
                if (value == null || value.isBlank()) {
                    throw bad("Template '" + name + "' " + where + "button " + (button.index() + 1) + " needs a value");
                }
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("type", "button");
                if ("COPY_CODE".equals(button.type())) {
                    node.put("sub_type", "copy_code");
                    node.put("index", index);
                    node.put("parameters", List.of(Map.of("type", "coupon_code", "coupon_code", value)));
                } else {
                    node.put("sub_type", "url");
                    node.put("index", index);
                    node.put("parameters", List.of(Map.of("type", "text", "text", value)));
                }
                out.add(node);
            } else if (inCarousel && "QUICK_REPLY".equals(button.type())) {
                // Carousel quick replies carry a payload, so a tap says which card it came from.
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("type", "button");
                node.put("sub_type", "quick_reply");
                node.put("index", index);
                node.put("parameters", List.of(Map.of("type", "payload", "payload", where.trim() + " button " + (button.index() + 1))));
                out.add(node);
            }
        }
        return out;
    }

    private static void requireCount(String name, String where, int needed, List<String> given) {
        List<String> values = given == null ? List.of() : given;
        if (values.size() != needed) {
            throw bad("Template '" + name + "' " + where + " needs " + needed + " value"
                    + (needed == 1 ? "" : "s") + ", got " + values.size());
        }
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == null || values.get(i).isBlank()) {
                throw bad("Template '" + name + "' " + where + " value {{" + (i + 1) + "}} is empty");
            }
        }
    }

    private static Map<String, Object> component(String type, List<Map<String, Object>> parameters) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", type);
        node.put("parameters", parameters);
        return node;
    }

    private static List<Map<String, Object>> texts(List<String> values) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String value : values) out.add(Map.of("type", "text", "text", value));
        return out;
    }

    private static Map<String, Object> media(String format, String link) {
        String kind = format.toLowerCase();
        return Map.of("type", kind, kind, Map.of("link", link));
    }

    static int maxVar(String text) {
        Matcher m = VAR.matcher(text == null ? "" : text);
        int max = 0;
        while (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        return max;
    }

    private JsonNode read(String json) {
        if (json == null || json.isBlank()) return objectMapper.createArrayNode();
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isArray() ? node : objectMapper.createArrayNode();
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    private List<String> readLinks(String json) {
        List<String> links = new ArrayList<>();
        for (JsonNode node : read(json)) links.add(node.isNull() ? null : node.asText(null));
        return links;
    }

    private static List<String> mapList(List<String> values, UnaryOperator<String> fn) {
        List<String> out = new ArrayList<>();
        if (values != null) for (String value : values) out.add(value == null ? null : fn.apply(value));
        return out;
    }

    private static Map<Integer, String> mapButtons(Map<Integer, String> values, UnaryOperator<String> fn) {
        Map<Integer, String> out = new HashMap<>();
        if (values != null) values.forEach((k, value) -> out.put(k, value == null ? null : fn.apply(value)));
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static BadRequestException bad(String message) {
        return new BadRequestException(message);
    }
}
