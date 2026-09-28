package com.xetax.crm.integration.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flattens an incoming webhook payload into dot-separated paths, so a field
 * mapping can point at a value nested inside the JSON instead of only at a
 * top-level key.
 *
 * <pre>
 *   {"customer": {"email": "a@b.com"}, "items": [{"sku": "X1"}]}
 *
 *   customer        -> {email=a@b.com}    (the container itself)
 *   customer.email  -> a@b.com
 *   items           -> [{sku=X1}]
 *   items.0         -> {sku=X1}
 *   items.0.sku     -> X1
 * </pre>
 *
 * <p>Containers keep an entry of their own on purpose. Before nesting was
 * supported, a mapping on "customer" stored the whole object in the CRM field,
 * and existing integrations that rely on that must keep working.
 *
 * <p>Every path segment is trimmed and lowercased, matching how sourceField is
 * normalised when a mapping is saved.
 *
 * <p>A key that already contains a dot ({@code {"user.name": "x"}}) produces the
 * same path as the equivalent nesting; whichever is visited last wins. Real
 * webhook payloads do not do both.
 */
final class PayloadFlattener {

    /** Deep enough for real webhooks, shallow enough that no payload recurses forever. */
    private static final int MAX_DEPTH = 10;

    /** Ceiling on the flattened map, so a huge array cannot exhaust memory. */
    private static final int MAX_ENTRIES = 2000;

    private PayloadFlattener() {
    }

    /**
     * @return every path in the payload, mapped to its value. Never null.
     */
    static Map<String, Object> flatten(Map<String, Object> payload) {
        Map<String, Object> flat = new LinkedHashMap<>();
        if (payload != null) {
            expand("", payload, flat, 0);
        }
        return flat;
    }

    /**
     * Paths that hold an actual value rather than a nested object or array —
     * the only ones worth telling a user about when a key went unmapped.
     */
    static List<String> leafPaths(Map<String, Object> flat) {
        List<String> leaves = new ArrayList<>();
        for (Map.Entry<String, Object> entry : flat.entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof Map) && !(value instanceof List)) {
                leaves.add(entry.getKey());
            }
        }
        return leaves;
    }

    private static void expand(String path, Object value, Map<String, Object> flat, int depth) {
        if (flat.size() >= MAX_ENTRIES) {
            return;
        }
        if (!path.isEmpty()) {
            flat.put(path, value);
        }
        if (depth >= MAX_DEPTH) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String segment = String.valueOf(entry.getKey()).trim().toLowerCase();
                expand(child(path, segment), entry.getValue(), flat, depth + 1);
            }
        } else if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                expand(child(path, String.valueOf(index)), list.get(index), flat, depth + 1);
            }
        }
    }

    private static String child(String path, String segment) {
        return path.isEmpty() ? segment : path + "." + segment;
    }
}
