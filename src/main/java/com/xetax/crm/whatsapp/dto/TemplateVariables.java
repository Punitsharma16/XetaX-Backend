package com.xetax.crm.whatsapp.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The values that fill a template's variables, in the same shape the
 * template has.
 *
 * <p>header: the {{1}} of a TEXT header. body: {{1}}, {{2}}… of the body.
 * buttons: button position → the value for a URL button ending in {{1}}, a
 * coupon code, or an OTP code. cards: the same again per carousel card.
 * A media link overrides the one saved on the template.
 *
 * <p>Each string may be a literal or a {fieldKey} placeholder — callers that
 * have a record or contact resolve those before the template is built.
 */
@Data
public class TemplateVariables {

    private List<String> header = new ArrayList<>();
    private String headerMediaUrl;
    private List<String> body = new ArrayList<>();
    private Map<Integer, String> buttons = new HashMap<>();
    private List<Card> cards = new ArrayList<>();

    @Data
    public static class Card {
        private String headerMediaUrl;
        private List<String> body = new ArrayList<>();
        private Map<Integer, String> buttons = new HashMap<>();
    }

    /** Body-only values — the shape older callers (playbook, legacy campaigns) have. */
    public static TemplateVariables ofBody(List<String> body) {
        TemplateVariables v = new TemplateVariables();
        if (body != null) v.setBody(new ArrayList<>(body));
        return v;
    }
}
