package com.xetax.crm.whatsapp;

import com.xetax.crm.common.util.PlaceholderResolver;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaceholderResolverTest {

    @Test
    void replacesKnownKeysAndBlanksUnknown() {
        Map<String, Object> data = Map.of("name", "Punit", "budget", 5000);
        assertEquals("Hi Punit, budget 5000 city ",
                PlaceholderResolver.resolve("Hi {name}, budget {budget} city {city}", data));
    }

    @Test
    void nullValueBecomesBlank() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", null);
        assertEquals("Hi ", PlaceholderResolver.resolve("Hi {name}", data));
    }

    @Test
    void textWithoutPlaceholdersIsUntouched() {
        assertEquals("plain text", PlaceholderResolver.resolve("plain text", Map.of()));
    }

    @Test
    void nullTextGivesEmpty() {
        assertEquals("", PlaceholderResolver.resolve(null, Map.of()));
    }
}
