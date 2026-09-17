package com.xetax.crm.whatsapp.service;

import com.xetax.crm.data_manager.dto.FieldResponse;
import com.xetax.crm.data_manager.enums.FieldType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Turns one Flow answer into the value a CRM field accepts.
 *
 * <p>Meta sends almost everything back as text — "500000" for a number,
 * "9876543210" for a phone — while record validation wants a real number, a
 * ten-digit phone, a parseable date. One mismatched answer used to fail the
 * whole record. Here each answer is converted to its field's type, and an
 * answer that cannot be is dropped (it stays on the submission) instead of
 * costing the customer their record.
 */
public final class FlowAnswerValues {

    private static final String EMAIL = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$";

    private FlowAnswerValues() {}

    /** The value to store, or null to leave this field out of the record. */
    public static Object convert(FieldResponse field, Object value, UnaryOperator<String> toNationalPhone) {
        if (value == null || field == null) return null;
        FieldType type = field.getFieldType() == null ? FieldType.TEXT : field.getFieldType();
        String text = asText(value);

        switch (type) {
            case NUMBER, DECIMAL -> {
                if (value instanceof Number) return value;
                String digits = text.replaceAll("[\\s,₹]", "");
                if (digits.isEmpty()) return null;
                try {
                    return new BigDecimal(digits);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            case BOOLEAN -> {
                if (value instanceof Boolean) return value;
                String lower = text.toLowerCase(Locale.ROOT);
                if (lower.equals("true") || lower.equals("yes") || lower.equals("1")) return Boolean.TRUE;
                if (lower.equals("false") || lower.equals("no") || lower.equals("0")) return Boolean.FALSE;
                return null;
            }
            case CHECKBOX -> {
                // An opt-in comes back as a boolean; keep it as the customer gave it.
                return value instanceof Boolean ? value : (text.isEmpty() ? null : text);
            }
            case PHONE -> {
                return text.isEmpty() ? null : toNationalPhone.apply(text);
            }
            case DATE -> {
                LocalDate date = date(text);
                return date == null ? null : date.toString();
            }
            case DATETIME -> {
                if (isDateTime(text)) return text;
                LocalDate date = date(text);
                return date == null ? null : date.atStartOfDay().toString();
            }
            case EMAIL -> {
                return text.matches(EMAIL) ? text : null;
            }
            default -> {
                return text.isEmpty() ? null : text;
            }
        }
    }

    private static String asText(Object value) {
        if (value instanceof Collection<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(", ")).trim();
        }
        return String.valueOf(value).trim();
    }

    /** yyyy-MM-dd, or the epoch-millisecond string older Flow versions send. */
    private static LocalDate date(String text) {
        if (text.isEmpty()) return null;
        try {
            return LocalDate.parse(text.length() > 10 && text.charAt(10) == 'T' ? text.substring(0, 10) : text);
        } catch (Exception ignored) {
            // fall through to epoch millis
        }
        if (text.matches("\\d{10,13}")) {
            long epoch = Long.parseLong(text);
            if (text.length() == 10) epoch *= 1000;
            return Instant.ofEpochMilli(epoch).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
        }
        return null;
    }

    private static boolean isDateTime(String text) {
        try {
            LocalDateTime.parse(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
