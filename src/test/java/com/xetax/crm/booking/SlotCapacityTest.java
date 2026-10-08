package com.xetax.crm.booking;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.booking.entity.BookingPage;
import com.xetax.crm.booking.entity.BookingSlot;
import com.xetax.crm.booking.entity.BookingStaff;
import com.xetax.crm.booking.entity.SlotBooking;
import com.xetax.crm.booking.enums.SlotStatus;
import com.xetax.crm.booking.repository.BookingPageRepository;
import com.xetax.crm.booking.repository.BookingSlotRepository;
import com.xetax.crm.booking.repository.BookingStaffRepository;
import com.xetax.crm.booking.repository.SlotBookingRepository;
import com.xetax.crm.booking.service.BookingService;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.template.repository.PackInstallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A slot takes one person unless the workspace says otherwise.
 *
 * <p>Default 1 is the whole point: a chair, an OPD turn and a technician's
 * visit stay exactly as they were, and only a workspace that runs group
 * sessions — a demo class, a batch — raises it. Rows written before capacity
 * existed carry a 0 for both columns, so they are read as a one-seat slot
 * rather than an unbookable one.
 */
class SlotCapacityTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String OWNER_ID = OWNER.toString();

    private BookingSlotRepository slots;
    private SlotBookingRepository slotBookings;
    private BookingStaffRepository staffRepository;
    private BookingService service;
    private BookingStaff neha;

    @BeforeEach
    void setUp() {
        BookingPageRepository pages = mock(BookingPageRepository.class);
        staffRepository = mock(BookingStaffRepository.class);
        slots = mock(BookingSlotRepository.class);
        slotBookings = mock(SlotBookingRepository.class);
        PackInstallRepository installs = mock(PackInstallRepository.class);
        FormRepo formRepo = mock(FormRepo.class);
        CurrentUserProvider users = mock(CurrentUserProvider.class);

        when(users.currentDataOwnerIdOrNull()).thenReturn(OWNER);
        when(slots.saveAll(any())).thenAnswer(call -> call.getArgument(0));
        when(slots.save(any())).thenAnswer(call -> call.getArgument(0));
        when(installs.findByOwnerUserIdAndPackKeyInOrderByInstalledAtDesc(anyString(), any()))
                .thenReturn(List.of());

        BookingPage page = BookingPage.builder().ownerUserId(OWNER_ID).formId(20L)
                .publicKey("KEY").enabled(false).title("Demo classes").slotMinutes(30).build();
        page.setId(1L);
        when(pages.findFirstByOwnerUserIdOrderByIdAsc(OWNER_ID)).thenReturn(Optional.of(page));

        neha = BookingStaff.builder().ownerUserId(OWNER_ID).name("Neha").active(true).sortOrder(0).build();
        neha.setId(7L);
        when(staffRepository.findByIdAndOwnerUserId(7L, OWNER_ID)).thenReturn(Optional.of(neha));
        when(staffRepository.findByOwnerUserIdOrderBySortOrderAscIdAsc(OWNER_ID)).thenReturn(List.of(neha));

        service = new BookingService(pages, staffRepository, slots, slotBookings, installs, formRepo, users);
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://app.xetacrm.pro");
        ReflectionTestUtils.setField(service, "apiBaseUrl", "https://api.xetacrm.pro");
    }

    private List<BookingSlot> addSlots(Integer capacity) {
        LocalDate day = LocalDate.now().plusDays(1);
        service.addSlots(new BookingService.SlotPlan(
                List.of(7L), day, day, LocalTime.of(10, 0), LocalTime.of(11, 0), 30, null, capacity));
        ArgumentCaptor<List<BookingSlot>> captor = ArgumentCaptor.forClass(List.class);
        verify(slots).saveAll(captor.capture());
        return captor.getValue();
    }

    /** The slot as the diary lists it. Also exercises the batched attendee lookup. */
    private Map<String, Object> listed(BookingSlot s, List<SlotBooking> attendees) {
        when(slots.findByOwnerUserIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                anyString(), any(), any())).thenReturn(List.of(s));
        when(slotBookings.findBySlotIdInOrderByIdAsc(any())).thenReturn(attendees);
        return service.slots(LocalDate.now(), LocalDate.now().plusDays(2)).get(0);
    }

    private BookingSlot slot(int capacity, int booked, SlotStatus status) {
        BookingSlot s = BookingSlot.builder()
                .ownerUserId(OWNER_ID).staffId(7L)
                .slotDate(LocalDate.now().plusDays(1)).startTime(LocalTime.of(10, 0))
                .durationMinutes(30).status(status).capacity(capacity).bookedCount(booked).build();
        s.setId(99L);
        when(slots.findByIdAndOwnerUserId(99L, OWNER_ID)).thenReturn(Optional.of(s));
        return s;
    }

    // ---------------------------------------------------------- the default

    @Test
    void aSlotTakesOnePersonWhenNoCapacityIsAskedFor() {
        for (BookingSlot s : addSlots(null)) {
            assertEquals(1, s.getCapacity());
            assertEquals(0, s.getBookedCount());
        }
    }

    @Test
    void aWorkspaceCanAskForAGroupSlot() {
        for (BookingSlot s : addSlots(12)) {
            assertEquals(12, s.getCapacity());
        }
    }

    @Test
    void refusesACapacityBelowOne() {
        assertThrows(BadRequestException.class, () -> addSlots(0));
        assertThrows(BadRequestException.class, () -> addSlots(-3));
    }

    @Test
    void refusesACapacityBeyondTheCeiling() {
        assertThrows(BadRequestException.class, () -> addSlots(51));
    }

    @Test
    void acceptsTheCeilingItself() {
        assertEquals(50, addSlots(50).get(0).getCapacity());
    }

    // ------------------------------------------------- what the panel sees

    @Test
    void theViewCountsTheSeatsLeft() {
        Map<String, Object> view = listed(slot(10, 4, SlotStatus.OPEN), List.of());

        assertEquals(10, view.get("capacity"));
        assertEquals(4, view.get("bookedCount"));
        assertEquals(6, view.get("seatsLeft"));
    }

    @Test
    void seatsLeftNeverGoesNegative() {
        // Should not happen, but the arithmetic must not report a negative.
        assertEquals(0, listed(slot(2, 5, SlotStatus.BLOCKED), List.of()).get("seatsLeft"));
    }

    // ------------------------------------------ slots from before capacity

    @Test
    void aBookedRowFromBeforeCapacityStillReadsAsBooked() {
        // Columns added to an existing table arrive as 0 on every row.
        slot(0, 0, SlotStatus.BOOKED);

        assertThrows(BadRequestException.class, () -> service.setSlotBlocked(99L, true));
    }

    @Test
    void aBookedRowFromBeforeCapacityCanStillBeCancelled() {
        slot(0, 0, SlotStatus.BOOKED);
        when(slots.release(99L, OWNER_ID)).thenReturn(1);
        when(slotBookings.findBySlotIdOrderByIdAsc(99L)).thenReturn(List.of());

        service.cancelBooking(99L);

        verify(slots).release(99L, OWNER_ID);
    }

    @Test
    void anOldRowIsReportedAsAOneSeatSlot() {
        Map<String, Object> view = listed(slot(0, 0, SlotStatus.OPEN), List.of());

        assertEquals(1, view.get("capacity"));
        assertEquals(1, view.get("seatsLeft"));
    }

    // -------------------------------------------------- cancelling a seat

    @Test
    void cancellingTheWholeSlotClearsEveryBooking() {
        slot(5, 3, SlotStatus.OPEN);
        when(slots.release(99L, OWNER_ID)).thenReturn(1);
        when(slotBookings.findBySlotIdOrderByIdAsc(99L)).thenReturn(List.of());

        service.cancelBooking(99L);

        verify(slots).release(99L, OWNER_ID);
        verify(slotBookings).deleteBySlotId(99L);
    }

    @Test
    void cancellingOneSeatLeavesTheOthersAlone() {
        BookingSlot s = slot(5, 3, SlotStatus.OPEN);
        SlotBooking leaving = booking(41L, "Asha");
        SlotBooking staying = booking(42L, "Ravi");
        when(slotBookings.findByIdAndOwnerUserId(41L, OWNER_ID)).thenReturn(Optional.of(leaving));
        when(slots.releaseOneSeat(99L, OWNER_ID)).thenReturn(1);
        when(slotBookings.findBySlotIdOrderByIdAsc(99L)).thenReturn(List.of(staying));

        service.cancelBooking(99L, 41L);

        verify(slotBookings).delete(leaving);
        verify(slotBookings, never()).deleteBySlotId(anyLong());
        // The slot's own fields mirror a booking, so they follow whoever is left.
        assertEquals("Ravi", s.getCustomerName());
    }

    @Test
    void cancellingTheLastSeatClearsTheSlotsOwnFields() {
        BookingSlot s = slot(1, 1, SlotStatus.BOOKED);
        s.setCustomerName("Asha");
        SlotBooking only = booking(41L, "Asha");
        when(slotBookings.findByIdAndOwnerUserId(41L, OWNER_ID)).thenReturn(Optional.of(only));
        when(slots.releaseOneSeat(99L, OWNER_ID)).thenReturn(1);
        when(slotBookings.findBySlotIdOrderByIdAsc(99L)).thenReturn(List.of());

        service.cancelBooking(99L, 41L);

        assertEquals(null, s.getCustomerName());
    }

    @Test
    void aSeatOnSomebodyElsesSlotCannotBeCancelled() {
        slot(5, 2, SlotStatus.OPEN);
        SlotBooking elsewhere = booking(41L, "Asha");
        elsewhere.setSlotId(1234L);
        when(slotBookings.findByIdAndOwnerUserId(41L, OWNER_ID)).thenReturn(Optional.of(elsewhere));

        assertThrows(RuntimeException.class, () -> service.cancelBooking(99L, 41L));
        verify(slots, never()).releaseOneSeat(anyLong(), anyString());
    }

    @Test
    void deletingASlotTakesItsBookingsWithIt() {
        slot(5, 0, SlotStatus.OPEN);

        service.deleteSlot(99L);

        verify(slotBookings).deleteBySlotId(99L);
        verify(slots).delete(any());
    }

    @Test
    void theAttendeesAreListedOnTheSlot() {
        Map<String, Object> view = listed(slot(5, 2, SlotStatus.OPEN),
                List.of(booking(41L, "Asha"), booking(42L, "Ravi")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> people = (List<Map<String, Object>>) view.get("bookings");
        assertEquals(2, people.size());
        assertEquals("Asha", people.get(0).get("customerName"));
        assertTrue(people.get(1).containsKey("recordId"));
    }

    private SlotBooking booking(Long id, String name) {
        SlotBooking b = SlotBooking.builder()
                .ownerUserId(OWNER_ID).slotId(99L).customerName(name).customerPhone("9999999999")
                .service("Haircut").recordId("rec-" + id).bookedVia("PAGE").build();
        b.setId(id);
        return b;
    }
}
