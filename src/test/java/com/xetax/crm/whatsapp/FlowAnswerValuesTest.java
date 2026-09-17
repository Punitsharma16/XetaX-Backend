package com.xetax.crm.whatsapp;

import com.xetax.crm.data_manager.dto.FieldResponse;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.whatsapp.service.FlowAnswerValues;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Meta answers in text; each CRM field type wants its own kind of value. */
class FlowAnswerValuesTest {

    private static Object convert(FieldType type, Object value) {
        FieldResponse field = new FieldResponse();
        field.setFieldKey("f");
        field.setFieldType(type);
        return FlowAnswerValues.convert(field, value, raw -> raw.replaceAll("\\D", "").length() >= 10
                ? raw.replaceAll("\\D", "").substring(raw.replaceAll("\\D", "").length() - 10) : null);
    }

    @Test
    void numbersBecomeNumbers() {
        assertEquals(new BigDecimal("500000"), convert(FieldType.NUMBER, "500000"));
        assertEquals(new BigDecimal("150000"), convert(FieldType.NUMBER, "1,50,000"));
        assertEquals(new BigDecimal("99.5"), convert(FieldType.DECIMAL, "₹ 99.5"));
        assertEquals(42, convert(FieldType.NUMBER, 42));
        assertNull(convert(FieldType.NUMBER, "about five lakh"), "unreadable is dropped, not a failed record");
        assertNull(convert(FieldType.NUMBER, ""));
    }

    @Test
    void phonesBecomeTenDigits() {
        assertEquals("9034908545", convert(FieldType.PHONE, "+91 90349 08545"));
        assertNull(convert(FieldType.PHONE, "123"));
    }

    @Test
    void yesAndNoBecomeBooleans() {
        assertEquals(true, convert(FieldType.BOOLEAN, "true"));
        assertEquals(false, convert(FieldType.BOOLEAN, "No"));
        assertEquals(true, convert(FieldType.BOOLEAN, true));
        assertNull(convert(FieldType.BOOLEAN, "maybe"));
        assertEquals(true, convert(FieldType.CHECKBOX, true), "an opt-in stays as given");
    }

    @Test
    void datesAreReadInTheShapesMetaSends() {
        assertEquals("2026-09-17", convert(FieldType.DATE, "2026-09-17"));
        assertEquals("2026-09-17", convert(FieldType.DATE, "1789603200000"), "epoch milliseconds");
        assertEquals("2026-09-17T00:00", convert(FieldType.DATETIME, "2026-09-17"));
        assertEquals("2026-09-17T10:30", convert(FieldType.DATETIME, "2026-09-17T10:30"));
        assertNull(convert(FieldType.DATE, "next week"));
    }

    @Test
    void emailsAreKeptOnlyWhenTheyLookLikeOne() {
        assertEquals("test@gmail.com", convert(FieldType.EMAIL, " test@gmail.com "));
        assertNull(convert(FieldType.EMAIL, "not an email"));
    }

    @Test
    void textStaysText() {
        assertEquals("WhatsApp", convert(FieldType.SELECT, "WhatsApp"));
        assertEquals("Testing of the flows", convert(FieldType.TEXTAREA, "Testing of the flows "));
        assertEquals("a, b", convert(FieldType.TEXT, List.of("a", "b")));
        assertNull(convert(FieldType.TEXT, "   "));
        assertNull(convert(FieldType.TEXT, null));
    }
}
