package com.xetax.crm.booking.service;

import java.util.List;
import java.util.Map;

/**
 * The free slots, written the way the bot's prompt reads them.
 *
 * <p>Kept apart from the conversation service because this text is the whole
 * guarantee behind "the bot only offers real times": the model never sees the
 * diary, only these lines, and it can book nothing but an id from them.
 */
public final class SlotsPrompt {

    private SlotsPrompt() { }

    public static final String HEADING = "AVAILABLE SLOTS";

    /** Empty for a business that takes no bookings, so its prompt is unchanged. */
    public static String render(boolean takesBookings, List<Map<String, Object>> slots) {
        if (!takesBookings) return "";
        if (slots == null || slots.isEmpty()) {
            return "\n" + HEADING + ":\n(none free right now — offer to have the team call back)\n";
        }
        StringBuilder out = new StringBuilder("\n" + HEADING + " (offer ONLY these, never another time):\n");
        for (Map<String, Object> slot : slots) {
            out.append("- id ").append(slot.get("id")).append(": ")
                    .append(slot.get("dayLabel")).append(", ").append(slot.get("label"))
                    .append(" with ").append(slot.get("staffName")).append('\n');
        }
        return out.toString();
    }
}
