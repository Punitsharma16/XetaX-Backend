package com.xetax.crm.booking.service;

import com.xetax.crm.automation.engine.AutomationEngine;
import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.booking.entity.BookingPage;
import com.xetax.crm.booking.entity.BookingSlot;
import com.xetax.crm.booking.entity.BookingStaff;
import com.xetax.crm.booking.enums.SlotStatus;
import com.xetax.crm.booking.repository.BookingPageRepository;
import com.xetax.crm.booking.repository.BookingSlotRepository;
import com.xetax.crm.booking.repository.BookingStaffRepository;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.dto.RecordRequest;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import com.xetax.crm.data_manager.service.FormMetaCache;
import com.xetax.crm.data_manager.validator.DynamicValidationService;
import com.xetax.crm.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The customer's side of bookings: which slots are still free, and taking one.
 * Runs without a signed-in user — the page's public key is the only credential
 * — and is also what the WhatsApp and website bots book through, so all three
 * ways into the diary obey exactly the same rules.
 *
 * <p>A booking is the one thing this feature writes into the CRM: it becomes a
 * record in the salon's form, through the same validation, default stage,
 * RECORD_CREATED automations and bell notification a hosted form uses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentService {

    /** The Hair Salon pack's field keys; only the ones the form still has are written. */
    static final String F_NAME = "customer_name";
    static final String F_PHONE = "phone";
    static final String F_SERVICE = "service";
    static final String F_STYLIST = "stylist";
    static final String F_DATE = "appointment_date";
    static final String F_TIME = "slot_time";
    static final String F_NOTES = "notes";

    /** How far ahead a customer is shown — a whole year of slots helps nobody choose. */
    private static final int LOOKAHEAD_DAYS = 60;
    private static final int MAX_SLOTS_SHOWN = 120;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    /**
     * "11:00 AM". Recent JDKs format the marker as "am", which looks like a
     * typo on a booking page and in the record the booking becomes.
     */
    private static String clock(LocalTime time) {
        return time.format(CLOCK).toUpperCase(Locale.ENGLISH);
    }

    private final BookingPageRepository pageRepository;
    private final BookingStaffRepository staffRepository;
    private final BookingSlotRepository slotRepository;
    private final FormRepo formRepo;
    private final FormMetaCache formMetaCache;
    private final DynamicValidationService validationService;
    private final RecordRepo recordRepo;
    private final AutomationEngine automationEngine;
    private final NotificationService notificationService;

    public record BookRequest(Long slotId, String name, String phone, String service, String note) {}

    /* ------------------------------------------------------- the public page */

    /** Everything the public page shows: the salon, its people, and free slots. */
    public Map<String, Object> storefront(String key) {
        BookingPage page = requireOpenPage(key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", page.getTitle());
        out.put("tagline", page.getTagline());
        out.put("note", page.getNote());
        out.put("services", services(page));
        out.put("days", availableDays(page.getOwnerUserId()));
        return out;
    }

    /**
     * Free slots, grouped by day, each carrying the person who would take it.
     * Only OPEN slots from this minute on are ever returned, which is what
     * makes "choose from available slots" true rather than a promise.
     */
    private List<Map<String, Object>> availableDays(String ownerUserId) {
        Map<Long, BookingStaff> people = new HashMap<>();
        for (BookingStaff staff : staffRepository.findByOwnerUserIdOrderBySortOrderAscIdAsc(ownerUserId)) {
            people.put(staff.getId(), staff);
        }
        LocalDate today = LocalDate.now();
        LocalDate last = today.plusDays(LOOKAHEAD_DAYS);

        Map<LocalDate, List<Map<String, Object>>> byDay = new LinkedHashMap<>();
        for (BookingSlot slot : slotRepository.findOpenFrom(ownerUserId, today, LocalTime.now())) {
            if (slot.getSlotDate().isAfter(last)) break;
            BookingStaff staff = people.get(slot.getStaffId());
            // A slot of someone who has been switched off is not on offer.
            if (staff == null || !staff.isActive()) continue;
            List<Map<String, Object>> day = byDay.computeIfAbsent(slot.getSlotDate(), d -> new ArrayList<>());
            if (byDay.values().stream().mapToInt(List::size).sum() >= MAX_SLOTS_SHOWN) break;
            day.add(publicSlot(slot, staff));
        }

        List<Map<String, Object>> days = new ArrayList<>();
        byDay.forEach((date, slots) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date.toString());
            row.put("label", date.format(DAY));
            row.put("slots", slots);
            days.add(row);
        });
        return days;
    }

    private Map<String, Object> publicSlot(BookingSlot slot, BookingStaff staff) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", slot.getId());
        view.put("time", slot.getStartTime().toString());
        view.put("label", clock(slot.getStartTime()));
        view.put("durationMinutes", slot.getDurationMinutes());
        view.put("staffId", staff.getId());
        view.put("staffName", staff.getName());
        view.put("staffRole", staff.getRole());
        return view;
    }

    /** The services on offer — read from the salon form's own Service field. */
    private List<String> services(BookingPage page) {
        if (page.getFormId() == null) return List.of();
        for (FormField field : formMetaCache.getFields(page.getFormId())) {
            if (!F_SERVICE.equals(field.getFieldKey())) continue;
            String options = field.getOptionsJson();
            if (options == null || options.isBlank()) return List.of();
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(options, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.warn("Service options unreadable on form {}: {}", page.getFormId(), e.getMessage());
                return List.of();
            }
        }
        return List.of();
    }

    /* ------------------------------------------------------------- booking */

    /** A customer confirming a slot on the public page. */
    @Transactional
    public Map<String, Object> book(String key, BookRequest request) {
        BookingPage page = requireOpenPage(key);
        return take(page, request, "PAGE", null);
    }

    /**
     * The same booking, made by a bot on WhatsApp or the website. The chat
     * already has a record for this customer, so that record becomes the
     * booking rather than a second one appearing beside it.
     */
    @Transactional
    public Map<String, Object> bookForOwner(String ownerUserId, BookRequest request, String existingRecordId) {
        BookingPage page = pageRepository.findFirstByOwnerUserIdOrderByIdAsc(ownerUserId)
                .orElseThrow(() -> new BadRequestException("This business is not taking bookings"));
        return take(page, request, "BOT", existingRecordId);
    }

    /** Open slots for a workspace — what a bot is allowed to offer. */
    public List<Map<String, Object>> openSlots(String ownerUserId, int limit) {
        Map<Long, BookingStaff> people = new HashMap<>();
        for (BookingStaff staff : staffRepository.findByOwnerUserIdOrderBySortOrderAscIdAsc(ownerUserId)) {
            people.put(staff.getId(), staff);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (BookingSlot slot : slotRepository.findOpenFrom(ownerUserId, LocalDate.now(), LocalTime.now())) {
            BookingStaff staff = people.get(slot.getStaffId());
            if (staff == null || !staff.isActive()) continue;
            Map<String, Object> row = publicSlot(slot, staff);
            row.put("date", slot.getSlotDate().toString());
            row.put("dayLabel", slot.getSlotDate().format(DAY));
            out.add(row);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** True when this workspace has a booking page that is switched on. */
    public boolean takesBookings(String ownerUserId) {
        return pageRepository.findFirstByOwnerUserIdOrderByIdAsc(ownerUserId)
                .filter(BookingPage::isEnabled)
                .filter(page -> page.getFormId() != null)
                .isPresent();
    }

    private Map<String, Object> take(BookingPage page, BookRequest request, String via, String existingRecordId) {
        if (request == null || request.slotId() == null) throw new BadRequestException("Choose a slot");
        String name = clean(request.name(), 160);
        String phone = clean(request.phone(), 32);
        if (name == null || name.isBlank()) throw new BadRequestException("Your name is needed");
        if (phone == null || phone.isBlank()) throw new BadRequestException("A phone number is needed");
        // Checked before the slot is claimed, not after: the form's own
        // validator runs when the record is written, and failing there would
        // hold a slot for the length of a transaction and hand the customer a
        // field-level error instead of a sentence about their number.
        if (phone.replaceAll("\\D", "").length() < 10) {
            throw new BadRequestException("Please give a valid phone number with the area code");
        }

        BookingSlot slot = slotRepository.findById(request.slotId())
                .filter(s -> s.getOwnerUserId().equals(page.getOwnerUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("That slot is not on offer"));
        if (slot.getStatus() != SlotStatus.OPEN) {
            throw new BadRequestException("That slot has just been taken — please pick another one.");
        }
        if (slot.getSlotDate().atTime(slot.getStartTime()).isBefore(LocalDateTime.now())) {
            throw new BadRequestException("That time has already passed — please pick another one.");
        }

        BookingStaff staff = staffRepository.findById(slot.getStaffId()).orElse(null);
        String service = clean(request.service(), 160);

        // The one moment that decides who gets the chair. Whoever's update
        // changes a row has the slot; everybody else is told it is gone.
        if (slotRepository.claim(slot.getId(), name, phone, service, via) == 0) {
            throw new BadRequestException("That slot has just been taken — please pick another one.");
        }

        RecordDocument record = writeRecord(page, slot, staff, name, phone, service,
                clean(request.note(), 500), existingRecordId);

        BookingSlot booked = slotRepository.findById(slot.getId()).orElseThrow();
        booked.setRecordId(record.getId());
        slotRepository.save(booked);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("slotId", slot.getId());
        out.put("date", slot.getSlotDate().toString());
        out.put("time", slot.getStartTime().toString());
        out.put("when", slot.getSlotDate().format(DAY) + " at " + clock(slot.getStartTime()));
        out.put("staffName", staff == null ? null : staff.getName());
        out.put("service", service);
        out.put("recordId", record.getId());
        out.put("message", "Booked! See you on " + slot.getSlotDate().format(DAY)
                + " at " + clock(slot.getStartTime()) + ".");
        return out;
    }

    /**
     * Every booking is one record. A bot chat already has the customer's
     * record open, so that one is filled in; anywhere else a new record starts
     * in the form's first stage, exactly as a hosted form would create it.
     */
    private RecordDocument writeRecord(BookingPage page, BookingSlot slot, BookingStaff staff,
                                       String name, String phone, String service, String note,
                                       String existingRecordId) {
        FormEntity form = formRepo.findById(page.getFormId())
                .orElseThrow(() -> new BadRequestException("This page is not taking bookings right now"));

        List<FormField> fields = formMetaCache.getFields(form.getId());
        Set<String> keys = new HashSet<>();
        for (FormField field : fields) keys.add(field.getFieldKey());

        Map<String, Object> data = new LinkedHashMap<>();
        putIf(keys, data, F_NAME, name);
        putIf(keys, data, F_PHONE, phone);
        putIf(keys, data, F_SERVICE, service);
        putIf(keys, data, F_STYLIST, staff == null ? null : staff.getName());
        putIf(keys, data, F_DATE, slot.getSlotDate().toString());
        putIf(keys, data, F_TIME, clock(slot.getStartTime()));
        putIf(keys, data, F_NOTES, note);

        RecordDocument existing = existingRecordId == null ? null
                : recordRepo.findById(existingRecordId)
                        .filter(r -> form.getId().equals(r.getFormId()))
                        .orElse(null);

        if (existing != null) {
            Map<String, Object> merged = existing.getData() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(existing.getData());
            merged.putAll(data);
            existing.setData(merged);
            existing.setUpdatedAt(LocalDateTime.now());
            RecordDocument saved = recordRepo.save(existing);
            notifyOwner(form, slot, staff, name, service, saved, "Appointment booked");
            return saved;
        }

        RecordRequest recordRequest = new RecordRequest();
        recordRequest.setData(data);
        Map<String, Object> validated = validationService.validate(recordRequest, fields);

        FormStage defaultStage = formMetaCache.getStages(form.getId()).stream()
                .filter(stage -> Boolean.TRUE.equals(stage.getIsDefault()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("This page is not taking bookings right now"));

        RecordDocument saved = recordRepo.save(RecordDocument.builder()
                .formId(form.getId())
                .stageId(defaultStage.getId())
                .data(validated)
                .createdBy("public-booking")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        automationEngine.execute(AutomationTrigger.RECORD_CREATED, form, saved);
        notifyOwner(form, slot, staff, name, service, saved, "New booking");
        return saved;
    }

    private void notifyOwner(FormEntity form, BookingSlot slot, BookingStaff staff,
                             String name, String service, RecordDocument record, String title) {
        notificationService.push(form.getOwnerUserId(), form.getOwnerUserId(), "RECORD_CREATED",
                title + " — " + slot.getSlotDate().format(DAY) + ", " + clock(slot.getStartTime()),
                name + (service == null || service.isBlank() ? "" : " · " + service)
                        + (staff == null ? "" : " · with " + staff.getName()),
                "/app/records/" + form.getSlug() + "/" + record.getId());
    }

    private BookingPage requireOpenPage(String key) {
        BookingPage page = pageRepository.findByPublicKey(key)
                .orElseThrow(() -> new ResourceNotFoundException("This booking page does not exist"));
        if (!page.isEnabled() || page.getFormId() == null) {
            throw new ResourceNotFoundException("This booking page is not open right now");
        }
        return page;
    }

    private static void putIf(Set<String> keys, Map<String, Object> data, String key, Object value) {
        if (value != null && keys.contains(key)) data.put(key, value);
    }

    private static String clean(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
