package com.xetax.crm.common.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {fieldKey} tokens with values from a data map, blank when absent.
 * Shared by SEND_EMAIL / SEND_WHATSAPP automations and WhatsApp campaigns
 * so placeholder behaviour stays identical everywhere.
 */
public final class PlaceholderResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    private PlaceholderResolver() {}

    public static String resolve(String text, Map<String, Object> data) {
        if (text == null || text.isEmpty() || data == null) {
            return text == null ? "" : text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = data.get(matcher.group(1));
            matcher.appendReplacement(
                    out, Matcher.quoteReplacement(value == null ? "" : value.toString()));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
