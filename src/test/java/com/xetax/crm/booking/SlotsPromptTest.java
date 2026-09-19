package com.xetax.crm.booking;

import com.xetax.crm.booking.service.SlotsPrompt;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the bot is allowed to offer. The model never reads the diary — it reads
 * these lines — so a time that is not here cannot be promised to a customer.
 */
class SlotsPromptTest {

    private static Map<String, Object> slot(long id, String day, String time, String who) {
        return Map.of("id", id, "dayLabel", day, "label", time, "staffName", who);
    }

    @Test
    void everyFreeSlotIsListedWithTheIdTheBotBooksWith() {
        String block = SlotsPrompt.render(true, List.of(
                slot(11L, "Sat, 20 Sep", "11:00 AM", "Neha"),
                slot(12L, "Sat, 20 Sep", "11:30 AM", "Riya")));

        assertTrue(block.contains("- id 11: Sat, 20 Sep, 11:00 AM with Neha"));
        assertTrue(block.contains("- id 12: Sat, 20 Sep, 11:30 AM with Riya"));
        assertTrue(block.contains("offer ONLY these"));
    }

    @Test
    void aFullDiarySaysSoInsteadOfLeavingTheBotToImprovise() {
        String block = SlotsPrompt.render(true, List.of());

        assertTrue(block.contains(SlotsPrompt.HEADING));
        assertTrue(block.contains("none free right now"));
    }

    @Test
    void abusinessThatTakesNoBookingsGetsNothingAddedToItsPrompt() {
        assertEquals("", SlotsPrompt.render(false, List.of(slot(11L, "Sat", "11:00 AM", "Neha"))));
    }
}
