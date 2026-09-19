package com.xetax.crm.booking;

import com.xetax.crm.automation.engine.AutomationEngine;
import com.xetax.crm.booking.entity.BookingPage;
import com.xetax.crm.booking.entity.BookingSlot;
import com.xetax.crm.booking.entity.BookingStaff;
import com.xetax.crm.booking.enums.SlotStatus;
import com.xetax.crm.booking.repository.BookingPageRepository;
import com.xetax.crm.booking.repository.BookingSlotRepository;
import com.xetax.crm.booking.repository.BookingStaffRepository;
import com.xetax.crm.booking.service.AppointmentService;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import com.xetax.crm.data_manager.service.FormMetaCache;
import com.xetax.crm.data_manager.validator.DynamicValidationService;
import com.xetax.crm.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The customer's side: what is on offer, and what happens when two people
 * reach for the same slot. A booking is also the one thing here that becomes
 * a CRM record, so the fields it writes are pinned down too.
 */
class AppointmentServiceTest {

    private static final String OWNER = "00000000-0000-0000-0000-000000000009";

    private BookingPageRepository pages;
    private BookingStaffRepository staffRepository;
    private BookingSlotRepository slots;
    private RecordRepo records;
    private AutomationEngine automations;
    private NotificationService notifications;
    private AppointmentService service;

    private BookingStaff neha;
    private BookingStaff offToday;
    private FormEntity form;

    @BeforeEach
    void setUp() {
        pages = mock(BookingPageRepository.class);
        staffRepository = mock(BookingStaffRepository.class);
        slots = mock(BookingSlotRepository.class);
        records = mock(RecordRepo.class);
        automations = mock(AutomationEngine.class);
        notifications = mock(NotificationService.class);
        FormRepo formRepo = mock(FormRepo.class);
        FormMetaCache meta = mock(FormMetaCache.class);
        DynamicValidationService validation = mock(DynamicValidationService.class);

        form = FormEntity.builder().name("Hair Salon Bookings").slug("hair-salon-bookings")
                .ownerUserId(OWNER).build();
        form.setId(20L);
        when(formRepo.findById(20L)).thenReturn(Optional.of(form));

        when(meta.getFields(20L)).thenReturn(List.of(
                field("customer_name", FieldType.TEXT, null),
                field("phone", FieldType.PHONE, null),
                field("service", FieldType.SELECT, "[\"Haircut\",\"Hair Spa\"]"),
                field("stylist", FieldType.TEXT, null),
                field("appointment_date", FieldType.DATE, null),
                field("slot_time", FieldType.TEXT, null),
                field("notes", FieldType.TEXTAREA, null)));

        FormStage booked = FormStage.builder().name("Booked").isDefault(true).build();
        booked.setId(90L);
        when(meta.getStages(20L)).thenReturn(List.of(booked));

        // The validator is the form's own; here it passes the data through.
        when(validation.validate(any(), any())).thenAnswer(call ->
                new LinkedHashMap<>(((com.xetax.crm.data_manager.dto.RecordRequest) call.getArgument(0)).getData()));

        when(records.save(any())).thenAnswer(call -> {
            RecordDocument doc = call.getArgument(0);
            if (doc.getId() == null) doc.setId("rec-1");
            return doc;
        });

        neha = staff(7L, "Neha", true);
        offToday = staff(8L, "Riya", false);
        when(staffRepository.findByOwnerUserIdOrderBySortOrderAscIdAsc(OWNER))
                .thenReturn(List.of(neha, offToday));
        when(staffRepository.findById(7L)).thenReturn(Optional.of(neha));
        when(staffRepository.findById(8L)).thenReturn(Optional.of(offToday));

        service = new AppointmentService(pages, staffRepository, slots, formRepo, meta,
                validation, records, automations, notifications);
    }

    private static FormField field(String key, FieldType type, String options) {
        return FormField.builder().fieldKey(key).label(key).fieldType(type).optionsJson(options).build();
    }

    private static BookingStaff staff(long id, String name, boolean active) {
        BookingStaff staff = BookingStaff.builder().ownerUserId(OWNER).name(name).active(active).build();
        staff.setId(id);
        return staff;
    }

    private BookingSlot slot(long id, long staffId, LocalDate date, LocalTime time, SlotStatus status) {
        BookingSlot slot = BookingSlot.builder().ownerUserId(OWNER).staffId(staffId).slotDate(date)
                .startTime(time).durationMinutes(30).status(status).build();
        slot.setId(id);
        when(slots.findById(id)).thenReturn(Optional.of(slot));
        return slot;
    }

    private BookingPage openPage() {
        BookingPage page = BookingPage.builder().ownerUserId(OWNER).formId(20L).publicKey("KEY")
                .enabled(true).title("Glow Salon").slotMinutes(30).build();
        page.setId(1L);
        when(pages.findByPublicKey("KEY")).thenReturn(Optional.of(page));
        when(pages.findFirstByOwnerUserIdOrderByIdAsc(OWNER)).thenReturn(Optional.of(page));
        return page;
    }

    /* ------------------------------------------------------- what is shown */

    @Test
    void onlyFreeSlotsOfPeopleWhoAreOnAreOffered() {
        openPage();
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        BookingSlot free = slot(1L, 7L, tomorrow, LocalTime.of(11, 0), SlotStatus.OPEN);
        BookingSlot someoneElsesDayOff = slot(2L, 8L, tomorrow, LocalTime.of(12, 0), SlotStatus.OPEN);
        when(slots.findOpenFrom(eq(OWNER), any(), any())).thenReturn(List.of(free, someoneElsesDayOff));

        Map<String, Object> page = service.storefront("KEY");
        List<Map<String, Object>> days = (List<Map<String, Object>>) page.get("days");
        List<Map<String, Object>> offered = (List<Map<String, Object>>) days.get(0).get("slots");

        assertEquals(1, days.size());
        assertEquals(1, offered.size());
        assertEquals("Neha", offered.get(0).get("staffName"));
    }

    @Test
    void theServicesComeFromTheFormsOwnField() {
        openPage();
        when(slots.findOpenFrom(eq(OWNER), any(), any())).thenReturn(List.of());

        Map<String, Object> page = service.storefront("KEY");

        assertEquals(List.of("Haircut", "Hair Spa"), page.get("services"));
    }

    @Test
    void aPageThatIsOffIsNotEvenReadable() {
        BookingPage page = openPage();
        page.setEnabled(false);

        assertThrows(ResourceNotFoundException.class, () -> service.storefront("KEY"));
    }

    /* ------------------------------------------------------------ booking */

    @Test
    void bookingClaimsTheSlotAndBecomesARecord() {
        openPage();
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        BookingSlot free = slot(1L, 7L, tomorrow, LocalTime.of(11, 0), SlotStatus.OPEN);
        when(slots.claim(eq(1L), anyString(), anyString(), any(), eq("PAGE"))).thenReturn(1);
        when(slots.save(any())).thenAnswer(call -> call.getArgument(0));

        Map<String, Object> result = service.book("KEY",
                new AppointmentService.BookRequest(1L, "Priya", "9896458807", "Haircut", "First visit"));

        assertEquals(true, result.get("ok"));
        assertEquals("rec-1", result.get("recordId"));

        var saved = org.mockito.ArgumentCaptor.forClass(RecordDocument.class);
        verify(records).save(saved.capture());
        Map<String, Object> data = saved.getValue().getData();
        assertEquals("Priya", data.get("customer_name"));
        assertEquals("9896458807", data.get("phone"));
        assertEquals("Haircut", data.get("service"));
        assertEquals("Neha", data.get("stylist"));
        assertEquals(tomorrow.toString(), data.get("appointment_date"));
        assertEquals("11:00 AM", data.get("slot_time"));
        assertEquals(90L, saved.getValue().getStageId());
        verify(automations).execute(any(), eq(form), any());
        verify(notifications).push(eq(OWNER), eq(OWNER), eq("RECORD_CREATED"), anyString(), anyString(), anyString());
    }

    @Test
    void theSecondPersonToConfirmTheSameSlotIsTurnedAway() {
        openPage();
        BookingSlot free = slot(1L, 7L, LocalDate.now().plusDays(1), LocalTime.of(11, 0), SlotStatus.OPEN);
        // The claim only changes a row while the slot is still OPEN.
        when(slots.claim(eq(1L), anyString(), anyString(), any(), anyString())).thenReturn(0);

        BadRequestException refused = assertThrows(BadRequestException.class, () -> service.book("KEY",
                new AppointmentService.BookRequest(1L, "Aarav", "9812345678", "Haircut", null)));

        assertTrue(refused.getMessage().contains("just been taken"));
        verify(records, never()).save(any());
    }

    @Test
    void aSlotThatIsAlreadyBookedIsNeverOffered() {
        openPage();
        slot(1L, 7L, LocalDate.now().plusDays(1), LocalTime.of(11, 0), SlotStatus.BOOKED);

        assertThrows(BadRequestException.class, () -> service.book("KEY",
                new AppointmentService.BookRequest(1L, "Aarav", "9812345678", null, null)));
        verify(slots, never()).claim(anyLong(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void aTimeThatHasPassedCannotBeBooked() {
        openPage();
        slot(1L, 7L, LocalDate.now().minusDays(1), LocalTime.of(11, 0), SlotStatus.OPEN);

        BadRequestException refused = assertThrows(BadRequestException.class, () -> service.book("KEY",
                new AppointmentService.BookRequest(1L, "Aarav", "9812345678", null, null)));
        assertTrue(refused.getMessage().contains("already passed"));
    }

    @Test
    void nameAndPhoneAreBothNeeded() {
        openPage();
        slot(1L, 7L, LocalDate.now().plusDays(1), LocalTime.of(11, 0), SlotStatus.OPEN);

        assertThrows(BadRequestException.class, () -> service.book("KEY",
                new AppointmentService.BookRequest(1L, "Aarav", "  ", null, null)));
    }

    /* ---------------------------------------------------------- from a bot */

    @Test
    void aBotBookingFillsTheChatsOwnRecordInsteadOfMakingASecond() {
        openPage();
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        slot(1L, 7L, tomorrow, LocalTime.of(11, 0), SlotStatus.OPEN);
        when(slots.claim(eq(1L), anyString(), anyString(), any(), eq("BOT"))).thenReturn(1);
        when(slots.save(any())).thenAnswer(call -> call.getArgument(0));

        RecordDocument fromChat = RecordDocument.builder().formId(20L).stageId(90L)
                .data(new LinkedHashMap<>(Map.of("customer_name", "Priya", "phone", "9896458807")))
                .build();
        fromChat.setId("chat-rec");
        when(records.findById("chat-rec")).thenReturn(Optional.of(fromChat));

        Map<String, Object> result = service.bookForOwner(OWNER,
                new AppointmentService.BookRequest(1L, "Priya", "9896458807", null, null), "chat-rec");

        assertEquals("chat-rec", result.get("recordId"));
        assertEquals("Neha", fromChat.getData().get("stylist"));
        assertEquals(tomorrow.toString(), fromChat.getData().get("appointment_date"));
        // One record, not two: the new-record path never ran.
        verify(automations, never()).execute(any(), any(), any());
    }

    @Test
    void aWorkspaceWithNoLivePageTakesNoBookings() {
        when(pages.findFirstByOwnerUserIdOrderByIdAsc(OWNER)).thenReturn(Optional.empty());

        assertFalse(service.takesBookings(OWNER));
        assertThrows(BadRequestException.class, () -> service.bookForOwner(OWNER,
                new AppointmentService.BookRequest(1L, "Priya", "9896458807", null, null), null));
    }

    @Test
    void openSlotsAreWhatABotMayOffer() {
        openPage();
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        BookingSlot free = slot(1L, 7L, tomorrow, LocalTime.of(11, 0), SlotStatus.OPEN);
        BookingSlot dayOff = slot(2L, 8L, tomorrow, LocalTime.of(12, 0), SlotStatus.OPEN);
        when(slots.findOpenFrom(eq(OWNER), any(), any())).thenReturn(List.of(free, dayOff));

        List<Map<String, Object>> offered = service.openSlots(OWNER, 10);

        assertEquals(1, offered.size());
        assertEquals(1L, offered.get(0).get("id"));
        assertEquals("Neha", offered.get(0).get("staffName"));
        assertTrue(service.takesBookings(OWNER));
    }
}
