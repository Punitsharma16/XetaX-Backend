package com.xetax.crm.booking.tools;

import com.xetax.crm.booking.service.BookingService;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI tools for the appointment book.
 *
 * <p>Read-heavy on purpose. "Kal kitni bookings hain" is the question people
 * actually ask; rearranging someone's diary from a chat message is not, and
 * building slots or staff is a few clicks on a page that shows the whole
 * week at once. Cancelling is here because a customer calling to cancel is a
 * real, urgent, one-line request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingTools {

    private final BookingService bookingService;

    @Tool(description = """
            READ-ONLY. The booking page's setup: whether it is switched on,
            its public link, the form bookings land in, the staff and the slot
            length. Use this for "is my booking page live" or "what's my
            booking link".
            """)
    @RequiresPermission("forms.view")
    public Map<String, Object> getBookingOverview() {
        try {
            return bookingService.overview();
        }
        catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = """
            READ-ONLY. The appointment slots between two dates (yyyy-MM-dd),
            with who they belong to, whether they are free, booked or
            blocked, and the customer on each booked one. Use this for "kal ki
            bookings", "is hafte kitne appointments hain" and anything about
            who is coming when. Leave the dates out for the next seven days.
            """)
    @RequiresPermission("forms.view")
    public Map<String, Object> getBookingSlots(
            @ToolParam(description = "From date yyyy-MM-dd", required = false) String fromDate,
            @ToolParam(description = "To date yyyy-MM-dd", required = false) String toDate) {
        try {
            LocalDate from = parseDate(fromDate, LocalDate.now());
            LocalDate to = parseDate(toDate, from.plusDays(7));
            List<Map<String, Object>> slots = bookingService.slots(from, to);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("from", from.toString());
            out.put("to", to.toString());
            out.put("count", slots.size());
            out.put("slots", slots);
            return out;
        }
        catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = """
            WRITE. Free a booked appointment slot — the customer cancelled.
            Argument slotId comes from getBookingSlots. Use ONLY on an
            explicit request. The CRM record the booking created is left
            alone; only the slot is freed.
            """)
    @RequiresPermission("forms.manage")
    public Map<String, Object> cancelBooking(
            @ToolParam(description = "Slot id from getBookingSlots") Long slotId) {
        try {
            Map<String, Object> out = new LinkedHashMap<>(bookingService.cancelBooking(slotId));
            out.put("cancelled", true);
            out.put("note", "The slot is free again. The record this booking created is "
                    + "still there.");
            return out;
        }
        catch (Exception e) {
            return Map.of("cancelled", false, "error", safeMessage(e));
        }
    }

    private static LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        }
        catch (Exception e) {
            throw new IllegalArgumentException(
                    "Could not read the date '" + value + "' — use yyyy-MM-dd.");
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? "The booking action could not be completed." : message;
    }
}
