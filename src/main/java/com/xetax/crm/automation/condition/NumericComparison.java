package com.xetax.crm.automation.condition;

import com.xetax.crm.automation.entity.AutomationCondition;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Shared reading for the two numeric operators.
 *
 * <p>The value compared against is usually a plain number, but it may also name
 * another field as <code>{other_key}</code> — which is the only way to write a
 * rule like "stock is under its own reorder level", where the threshold is per
 * record rather than fixed.
 *
 * <p>Anything that is not a number on either side makes the condition false
 * rather than throwing: an automation that cannot be judged must not fire, and
 * must not take the save down with it.
 */
final class NumericComparison {

    private NumericComparison() {}

    /** The record's value for the condition's field, or null if it is not a number. */
    static BigDecimal left(AutomationCondition condition, Map<String, Object> record) {
        return number(record.get(condition.getFormField().getFieldKey()));
    }

    /** The threshold: a literal, or another field of the same record. */
    static BigDecimal right(AutomationCondition condition, Map<String, Object> record) {
        String raw = condition.getValue();
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.length() > 2 && trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return number(record.get(trimmed.substring(1, trimmed.length() - 1).trim()));
        }
        return number(trimmed);
    }

    private static BigDecimal number(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        try {
            String text = value.toString().trim();
            return text.isEmpty() ? null : new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
