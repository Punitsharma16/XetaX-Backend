package com.xetax.crm.booking;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.booking.entity.BookingPage;
import com.xetax.crm.booking.entity.BookingSlot;
import com.xetax.crm.booking.entity.BookingStaff;
import com.xetax.crm.booking.enums.SlotStatus;
import com.xetax.crm.booking.repository.BookingPageRepository;
import com.xetax.crm.booking.repository.BookingSlotRepository;
import com.xetax.crm.booking.repository.BookingStaffRepository;
import com.xetax.crm.booking.service.BookingService;
import com.xetax.crm.booking.service.BookingService.SlotPlan;
import com.xetax.crm.booking.service.BookingService.StaffInput;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.template.entity.PackInstall;
import com.xetax.crm.template.repository.PackInstallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The salon owner's side of the diary: who takes appointments, and filling a
 * stretch of their day with slots.
 */
class BookingServiceTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000009");

    private BookingPageRepository pages;
    private BookingStaffRepository staffRepository;
    private BookingSlotRepository slots;
    private BookingService service;
    private BookingStaff neha;

    @BeforeEach
    void setUp() {
        pages = mock(BookingPageRepository.class);
        staffRepository = mock(BookingStaffRepository.class);
        slots = mock(BookingSlotRepository.class);
        PackInstallRepository installs = mock(PackInstallRepository.class);
        FormRepo formRepo = mock(FormRepo.class);
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        when(users.currentDataOwnerIdOrNull()).thenReturn(OWNER);

        when(pages.save(any())).thenAnswer(call -> {
            BookingPage page = call.getArgument(0);
            if (page.getId() == null) page.setId(1L);
            return page;
        });
        when(staffRepository.save(any())).thenAnswer(call -> {
            BookingStaff staff = call.getArgument(0);
            if (staff.getId() == null) staff.setId(7L);
            return staff;
        });
        when(slots.saveAll(any())).thenAnswer(call -> call.getArgument(0));

        PackInstall install = PackInstall.builder()
                .ownerUserId(OWNER.toString()).packKey("salon").formId(20L).build();
        when(installs.findByOwnerUserIdAndPackKeyOrderByInstalledAtDesc(OWNER.toString(), "salon"))
                .thenReturn(List.of(install));
        FormEntity form = FormEntity.builder().name("Hair Salon Bookings").slug("hair-salon-bookings")
                .ownerUserId(OWNER.toString()).build();
        form.setId(20L);
        when(formRepo.findById(20L)).thenReturn(Optional.of(form));

        neha = BookingStaff.builder().ownerUserId(OWNER.toString()).name("Neha")
                .role("Senior stylist").active(true).sortOrder(0).build();
        neha.setId(7L);
        when(staffRepository.findByIdAndOwnerUserId(7L, OWNER.toString())).thenReturn(Optional.of(neha));
        when(staffRepository.findByOwnerUserIdOrderBySortOrderAscIdAsc(OWNER.toString()))
                .thenReturn(List.of(neha));

        service = new BookingService(pages, staffRepository, slots, installs, formRepo, users);
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://app.xetacrm.pro");
        ReflectionTestUtils.setField(service, "apiBaseUrl", "https://api.xetacrm.pro");
    }

    private BookingPage existingPage() {
        BookingPage page = BookingPage.builder().ownerUserId(OWNER.toString()).formId(20L)
                .publicKey("KEY").enabled(false).title("Glow Salon").slotMinutes(30).build();
        page.setId(1L);
        when(pages.findFirstByOwnerUserIdOrderByIdAsc(OWNER.toString())).thenReturn(Optional.of(page));
        return page;
    }

    /* ---------------------------------------------------------------- page */

    @Test
    void theFirstVisitCreatesAnOffPagePointedAtTheSalonForm() {
        when(pages.findFirstByOwnerUserIdOrderByIdAsc(OWNER.toString())).thenReturn(Optional.empty());

        Map<String, Object> page = (Map<String, Object>) service.overview().get("page");

        assertEquals(20L, page.get("formId"));
        assertEquals(false, page.get("enabled"));
        assertEquals("Hair Salon Bookings", page.get("title"));
        assertTrue(String.valueOf(page.get("publicUrl")).startsWith("https://app.xetacrm.pro/book/"));
    }

    @Test
    void aPageWithNoFormCannotGoLive() {
        BookingPage page = existingPage();
        page.setFormId(null);

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.updatePage(new BookingService.PageUpdate(null, true, null, null, null, null)));
        assertTrue(refused.getMessage().contains("form"));
    }

    @Test
    void bookingsOnlyGoIntoThisWorkspacesSalonForm() {
        existingPage();

        assertThrows(BadRequestException.class,
                () -> service.updatePage(new BookingService.PageUpdate(999L, null, null, null, null, null)));
    }

    /* --------------------------------------------------------------- staff */

    @Test
    void aPersonNeedsAName() {
        existingPage();

        assertThrows(BadRequestException.class, () -> service.createStaff(new StaffInput("  ", null, null, null)));
    }

    @Test
    void someoneWithBookedAppointmentsIsNotDeleted() {
        existingPage();
        when(slots.countByStaffIdAndStatus(7L, SlotStatus.BOOKED)).thenReturn(2L);

        BadRequestException refused = assertThrows(BadRequestException.class, () -> service.deleteStaff(7L));
        assertTrue(refused.getMessage().contains("switch them off"));
    }

    /* --------------------------------------------------------------- slots */

    @Test
    void aDayIsFilledSlotBySlot() {
        existingPage();
        LocalDate day = LocalDate.now().plusDays(1);

        Map<String, Object> result = service.addSlots(new SlotPlan(
                List.of(7L), day, day, LocalTime.of(10, 0), LocalTime.of(12, 0), 30, null));

        // 10:00, 10:30, 11:00, 11:30 — and nothing that would end after closing.
        assertEquals(4, result.get("added"));
    }

    @Test
    void slotsThatAlreadyExistAreLeftAlone() {
        existingPage();
        LocalDate day = LocalDate.now().plusDays(1);
        when(slots.existsByStaffIdAndSlotDateAndStartTime(7L, day, LocalTime.of(10, 0))).thenReturn(true);

        Map<String, Object> result = service.addSlots(new SlotPlan(
                List.of(7L), day, day, LocalTime.of(10, 0), LocalTime.of(11, 0), 30, null));

        assertEquals(1, result.get("added"));
        assertEquals(1, result.get("alreadyThere"));
    }

    @Test
    void onlyTheChosenWeekdaysGetSlots() {
        existingPage();
        LocalDate monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY).plusWeeks(1);

        Map<String, Object> result = service.addSlots(new SlotPlan(
                List.of(7L), monday, monday.plusDays(6), LocalTime.of(10, 0), LocalTime.of(11, 0), 60,
                List.of(6, 7)));   // Saturday and Sunday only

        assertEquals(2, result.get("added"));
    }

    @Test
    void aClosingTimeBeforeOpeningIsRefused() {
        existingPage();
        LocalDate day = LocalDate.now().plusDays(1);

        assertThrows(BadRequestException.class, () -> service.addSlots(new SlotPlan(
                List.of(7L), day, day, LocalTime.of(18, 0), LocalTime.of(10, 0), 30, null)));
    }

    @Test
    void aBookedSlotIsNotDeletedByMistake() {
        existingPage();
        BookingSlot booked = BookingSlot.builder().ownerUserId(OWNER.toString()).staffId(7L)
                .slotDate(LocalDate.now().plusDays(1)).startTime(LocalTime.of(11, 0))
                .durationMinutes(30).status(SlotStatus.BOOKED).customerName("Priya").build();
        booked.setId(50L);
        when(slots.findByIdAndOwnerUserId(50L, OWNER.toString())).thenReturn(Optional.of(booked));

        BadRequestException refused = assertThrows(BadRequestException.class, () -> service.deleteSlot(50L));
        assertTrue(refused.getMessage().contains("cancel the booking"));
    }

    @Test
    void cancellingPutsTheSlotBackOnOffer() {
        existingPage();
        BookingSlot booked = BookingSlot.builder().ownerUserId(OWNER.toString()).staffId(7L)
                .slotDate(LocalDate.now().plusDays(1)).startTime(LocalTime.of(11, 0))
                .durationMinutes(30).status(SlotStatus.BOOKED).customerName("Priya").build();
        booked.setId(50L);
        BookingSlot freed = BookingSlot.builder().ownerUserId(OWNER.toString()).staffId(7L)
                .slotDate(booked.getSlotDate()).startTime(booked.getStartTime())
                .durationMinutes(30).status(SlotStatus.OPEN).build();
        freed.setId(50L);
        when(slots.findByIdAndOwnerUserId(50L, OWNER.toString()))
                .thenReturn(Optional.of(booked), Optional.of(freed));
        when(slots.release(eq(50L), eq(OWNER.toString()))).thenReturn(1);

        Map<String, Object> view = service.cancelBooking(50L);

        assertEquals("OPEN", view.get("status"));
        assertNull(view.get("customerName"));
    }
}
