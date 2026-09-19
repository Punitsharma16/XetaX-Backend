package com.xetax.crm.booking.controller;

import com.xetax.crm.booking.service.BookingService;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The salon's own bookings page: the people, their slots, and the public link
 * customers book from. Diary only — the records live in the salon's form.
 */
@RestController
@RequestMapping("/api/booking")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @GetMapping
    @RequiresPermission("forms.view")
    public ApiResponse<Map<String, Object>> overview() {
        return ResponseUtil.success("Bookings", bookingService.overview());
    }

    @PutMapping("/page")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> updatePage(@RequestBody BookingService.PageUpdate update) {
        return ResponseUtil.success("Booking page saved", bookingService.updatePage(update));
    }

    @PostMapping("/page/regenerate-key")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> regenerateKey() {
        return ResponseUtil.success("New link created", bookingService.regenerateKey());
    }

    /* --------------------------------------------------------------- staff */

    @PostMapping("/staff")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> createStaff(@RequestBody BookingService.StaffInput input) {
        return ResponseUtil.success("Added", bookingService.createStaff(input));
    }

    @PutMapping("/staff/{id}")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> updateStaff(@PathVariable Long id,
                                                        @RequestBody BookingService.StaffInput input) {
        return ResponseUtil.success("Saved", bookingService.updateStaff(id, input));
    }

    @DeleteMapping("/staff/{id}")
    @RequiresPermission("forms.manage")
    public ApiResponse<Void> deleteStaff(@PathVariable Long id) {
        bookingService.deleteStaff(id);
        return ResponseUtil.success("Removed");
    }

    /* --------------------------------------------------------------- slots */

    @GetMapping("/slots")
    @RequiresPermission("forms.view")
    public ApiResponse<List<Map<String, Object>>> slots(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseUtil.success("Slots", bookingService.slots(from, to));
    }

    @PostMapping("/slots")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> addSlots(@RequestBody BookingService.SlotPlan plan) {
        return ResponseUtil.success("Slots added", bookingService.addSlots(plan));
    }

    @DeleteMapping("/slots/{id}")
    @RequiresPermission("forms.manage")
    public ApiResponse<Void> deleteSlot(@PathVariable Long id) {
        bookingService.deleteSlot(id);
        return ResponseUtil.success("Slot removed");
    }

    @PutMapping("/slots/{id}/blocked")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> setBlocked(@PathVariable Long id,
                                                       @RequestParam boolean blocked) {
        return ResponseUtil.success(blocked ? "Slot kept back" : "Slot open again",
                bookingService.setSlotBlocked(id, blocked));
    }

    @PostMapping("/slots/{id}/cancel")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> cancelBooking(@PathVariable Long id) {
        return ResponseUtil.success("Booking cancelled", bookingService.cancelBooking(id));
    }
}
