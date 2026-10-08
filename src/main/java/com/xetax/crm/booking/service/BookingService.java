package com.xetax.crm.booking.service;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.booking.entity.BookingPage;
import com.xetax.crm.booking.entity.BookingSlot;
import com.xetax.crm.booking.entity.SlotBooking;
import com.xetax.crm.booking.entity.BookingStaff;
import com.xetax.crm.booking.enums.SlotStatus;
import com.xetax.crm.booking.repository.BookingPageRepository;
import com.xetax.crm.booking.repository.BookingSlotRepository;
import com.xetax.crm.booking.repository.SlotBookingRepository;
import com.xetax.crm.booking.repository.BookingStaffRepository;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.template.entity.PackInstall;
import com.xetax.crm.template.repository.PackInstallRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * The salon's own side of bookings: the people who take appointments, the
 * slots they are free in, and the public page customers pick from.
 *
 * <p>Nothing here is a CRM record. The only record this feature creates is a
 * confirmed booking (see {@link AppointmentService}), so the pipeline carries
 * appointments and nothing else.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BookingService {

    /** The vertical pack whose installs can take bookings. */
    /**
     * Packs whose workspaces get the booking diary.
     *
     * <p>All four sell a named person's time in dated slots: a stylist's chair,
     * a doctor's OPD, a technician's visit window, a demo session. Restaurant
     * and Sales are deliberately out — a table booking is not an order, and
     * Sales already has its own meeting scheduler.
     */
    public static final Set<String> PACK_KEYS = Set.of("salon", "hospital", "services", "coaching");

    /** A day's slots are added in one go; this caps a careless date range. */
    private static final int MAX_SLOTS_PER_REQUEST = 500;
    /** A sane ceiling for a group session — a class, not a stadium. */
    static final int MAX_SLOT_CAPACITY = 50;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BookingPageRepository pageRepository;
    private final BookingStaffRepository staffRepository;
    private final BookingSlotRepository slotRepository;
    private final SlotBookingRepository slotBookingRepository;
    private final PackInstallRepository packInstallRepository;
    private final FormRepo formRepo;
    private final CurrentUserProvider currentUserProvider;

    @Value("${app.public-base-url:http://localhost:5000}")
    private String publicBaseUrl;

    @Value("${app.api-base-url:http://localhost:8085}")
    private String apiBaseUrl;

    public record PageUpdate(Long formId, Boolean enabled, String title, String tagline,
                             String note, Integer slotMinutes) {}

    public record StaffInput(String name, String role, Boolean active, Integer sortOrder) {}

    /** One call adds a whole stretch of a person's diary. */
    /** capacity is how many people each slot takes; left out it means one. */
    public record SlotPlan(List<Long> staffIds, LocalDate fromDate, LocalDate toDate,
                           LocalTime startTime, LocalTime endTime, Integer durationMinutes,
                           List<Integer> weekdays, Integer capacity) {}

    private String owner() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    /* ------------------------------------------------------------ overview */

    /** Everything the bookings page needs in one call. The page row is created on first visit. */
    public Map<String, Object> overview() {
        BookingPage page = myPage();
        LocalDate today = LocalDate.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", pageView(page));
        out.put("bookingForms", bookingForms());
        out.put("staff", staffRepository.findByOwnerUserIdOrderBySortOrderAscIdAsc(owner())
                .stream().map(this::staffView).toList());
        out.put("slots", slots(today, today.plusDays(14)));
        return out;
    }

    public Map<String, Object> updatePage(PageUpdate update) {
        BookingPage page = myPage();
        if (update.formId() != null) {
            boolean allowed = bookingForms().stream()
                    .anyMatch(form -> update.formId().equals(form.get("id")));
            if (!allowed) throw new BadRequestException("Bookings can only go into a Hair Salon form of yours");
            page.setFormId(update.formId());
        }
        if (update.title() != null) page.setTitle(trimTo(update.title(), 120));
        if (update.tagline() != null) page.setTagline(trimTo(update.tagline(), 255));
        if (update.note() != null) page.setNote(trimTo(update.note(), 500));
        if (update.slotMinutes() != null) {
            int minutes = update.slotMinutes();
            if (minutes < 5 || minutes > 480) throw new BadRequestException("A slot is between 5 and 480 minutes");
            page.setSlotMinutes(minutes);
        }
        if (update.enabled() != null) {
            if (update.enabled() && page.getFormId() == null) {
                throw new BadRequestException(
                        "Choose which form bookings go into before switching the page on");
            }
            page.setEnabled(update.enabled());
        }
        return pageView(pageRepository.save(page));
    }

    /** A new key: old links and printed QR codes stop working from this moment. */
    public Map<String, Object> regenerateKey() {
        BookingPage page = myPage();
        page.setPublicKey(newKey());
        return pageView(pageRepository.save(page));
    }

    /* --------------------------------------------------------------- staff */

    public Map<String, Object> createStaff(StaffInput input) {
        String name = input == null ? null : trimTo(input.name(), 120);
        if (name == null || name.isBlank()) throw new BadRequestException("A name is needed");
        BookingStaff staff = BookingStaff.builder()
                .ownerUserId(owner())
                .name(name)
                .role(trimTo(input.role(), 160))
                .active(input.active() == null || input.active())
                .sortOrder(input.sortOrder() == null ? 0 : input.sortOrder())
                .build();
        return staffView(staffRepository.save(staff));
    }

    public Map<String, Object> updateStaff(Long id, StaffInput input) {
        BookingStaff staff = myStaff(id);
        if (input.name() != null) {
            String name = trimTo(input.name(), 120);
            if (name.isBlank()) throw new BadRequestException("A name is needed");
            staff.setName(name);
        }
        if (input.role() != null) staff.setRole(trimTo(input.role(), 160));
        if (input.active() != null) staff.setActive(input.active());
        if (input.sortOrder() != null) staff.setSortOrder(input.sortOrder());
        return staffView(staffRepository.save(staff));
    }

    /**
     * Removing someone takes their free slots with them, but never a booked
     * one: an appointment a customer is holding must not vanish, so the person
     * is switched off instead and their diary stays readable.
     */
    public void deleteStaff(Long id) {
        BookingStaff staff = myStaff(id);
        if (slotRepository.countByStaffIdAndStatus(staff.getId(), SlotStatus.BOOKED) > 0) {
            throw new BadRequestException(
                    "This person has booked appointments — switch them off instead of deleting.");
        }
        slotRepository.deleteByStaffId(staff.getId());
        staffRepository.delete(staff);
    }

    /* --------------------------------------------------------------- slots */

    /**
     * Fills a stretch of diary: for every chosen person, every chosen weekday
     * between the two dates, a slot every {@code durationMinutes} from start to
     * end. Slots that already exist are left exactly as they are, so running it
     * twice never disturbs a booking.
     */
    public Map<String, Object> addSlots(SlotPlan plan) {
        String own = owner();
        BookingPage page = myPage();
        if (plan == null || plan.staffIds() == null || plan.staffIds().isEmpty()) {
            throw new BadRequestException("Choose at least one person");
        }
        LocalDate from = plan.fromDate() == null ? LocalDate.now() : plan.fromDate();
        LocalDate to = plan.toDate() == null ? from : plan.toDate();
        if (to.isBefore(from)) throw new BadRequestException("The last day is before the first one");
        if (from.isBefore(LocalDate.now().minusDays(1))) {
            throw new BadRequestException("Slots can only be added from today onwards");
        }
        LocalTime start = plan.startTime() == null ? LocalTime.of(10, 0) : plan.startTime();
        LocalTime end = plan.endTime() == null ? LocalTime.of(19, 0) : plan.endTime();
        if (!end.isAfter(start)) throw new BadRequestException("The closing time is before the opening time");

        int minutes = plan.durationMinutes() == null ? page.getSlotMinutes() : plan.durationMinutes();
        if (minutes < 5 || minutes > 480) throw new BadRequestException("A slot is between 5 and 480 minutes");

        // One person per slot unless the workspace runs group sessions.
        int capacity = plan.capacity() == null ? 1 : plan.capacity();
        if (capacity < 1 || capacity > MAX_SLOT_CAPACITY) {
            throw new BadRequestException("A slot takes between 1 and " + MAX_SLOT_CAPACITY + " people");
        }

        Set<Integer> weekdays = plan.weekdays() == null || plan.weekdays().isEmpty()
                ? null : new HashSet<>(plan.weekdays());

        List<BookingStaff> people = new ArrayList<>();
        for (Long staffId : plan.staffIds()) people.add(myStaff(staffId));

        List<BookingSlot> created = new ArrayList<>();
        int skipped = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (weekdays != null && !weekdays.contains(day.getDayOfWeek().getValue())) continue;
            for (BookingStaff staff : people) {
                for (LocalTime at = start; !at.plusMinutes(minutes).isAfter(end); at = at.plusMinutes(minutes)) {
                    if (created.size() >= MAX_SLOTS_PER_REQUEST) {
                        throw new BadRequestException(
                                "That is more than " + MAX_SLOTS_PER_REQUEST
                                + " slots at once — add a shorter stretch of days.");
                    }
                    if (slotRepository.existsByStaffIdAndSlotDateAndStartTime(staff.getId(), day, at)) {
                        skipped++;
                        continue;
                    }
                    created.add(BookingSlot.builder()
                            .ownerUserId(own)
                            .staffId(staff.getId())
                            .slotDate(day)
                            .startTime(at)
                            .durationMinutes(minutes)
                            .status(SlotStatus.OPEN)
                            .capacity(capacity)
                            .bookedCount(0)
                            .build());
                }
            }
        }
        slotRepository.saveAll(created);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("added", created.size());
        out.put("alreadyThere", skipped);
        return out;
    }

    /** The diary between two dates, newest day first in the panel's own order. */
    public List<Map<String, Object>> slots(LocalDate from, LocalDate to) {
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? start.plusDays(14) : to;
        Map<Long, BookingStaff> people = new HashMap<>();
        for (BookingStaff staff : staffRepository.findByOwnerUserIdOrderBySortOrderAscIdAsc(owner())) {
            people.put(staff.getId(), staff);
        }
        List<BookingSlot> found = slotRepository
                .findByOwnerUserIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(owner(), start, end);

        // One query for every attendee on the page rather than one per slot.
        Map<Long, List<SlotBooking>> bySlot = new HashMap<>();
        List<Long> ids = found.stream().map(BookingSlot::getId).toList();
        if (!ids.isEmpty()) {
            for (SlotBooking booking : slotBookingRepository.findBySlotIdInOrderByIdAsc(ids)) {
                bySlot.computeIfAbsent(booking.getSlotId(), k -> new ArrayList<>()).add(booking);
            }
        }
        return found.stream()
                .map(slot -> slotView(slot, people.get(slot.getStaffId()),
                        bySlot.getOrDefault(slot.getId(), List.of())))
                .toList();
    }

    /** A free slot can go; a booked one is cancelled first, on purpose. */
    public void deleteSlot(Long id) {
        BookingSlot slot = mySlot(id);
        if (slot.getStatus() == SlotStatus.BOOKED) {
            throw new BadRequestException(
                    "Someone has this appointment — cancel the booking first.");
        }
        slotBookingRepository.deleteBySlotId(slot.getId());
        slotRepository.delete(slot);
    }

    /** Keeps a free slot back (leave, break), or puts a kept-back one out again. */
    public Map<String, Object> setSlotBlocked(Long id, boolean blocked) {
        BookingSlot slot = mySlot(id);
        if (seatsTaken(slot) > 0) {
            throw new BadRequestException("This slot is booked — cancel the booking first.");
        }
        slot.setStatus(blocked ? SlotStatus.BLOCKED : SlotStatus.OPEN);
        BookingSlot saved = slotRepository.save(slot);
        return slotView(saved, staffRepository.findByIdAndOwnerUserId(saved.getStaffId(), owner()).orElse(null));
    }

    /**
     * Frees a booked slot. The record the booking became is left alone — the
     * appointment happened in the business's history whatever the diary says,
     * and its own pipeline has a stage for a cancellation.
     */
    public Map<String, Object> cancelBooking(Long id) {
        BookingSlot slot = mySlot(id);
        if (seatsTaken(slot) == 0) {
            throw new BadRequestException("This slot is not booked");
        }
        slotRepository.release(slot.getId(), owner());
        slotBookingRepository.deleteBySlotId(slot.getId());
        BookingSlot fresh = mySlot(id);
        return slotView(fresh, staffRepository.findByIdAndOwnerUserId(fresh.getStaffId(), owner()).orElse(null));
    }

    /**
     * Frees one person's seat on a group slot, leaving the others where they
     * are. The slot's own customer fields mirror a booking, so they are
     * rewritten from whoever is left — or cleared when nobody is.
     */
    public Map<String, Object> cancelBooking(Long slotId, Long bookingId) {
        String own = owner();
        BookingSlot slot = mySlot(slotId);
        SlotBooking booking = slotBookingRepository.findByIdAndOwnerUserId(bookingId, own)
                .filter(b -> b.getSlotId().equals(slotId))
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        slotBookingRepository.delete(booking);
        if (slotRepository.releaseOneSeat(slotId, own) == 0) {
            throw new BadRequestException("This slot is not booked");
        }

        BookingSlot fresh = mySlot(slotId);
        List<SlotBooking> left = slotBookingRepository.findBySlotIdOrderByIdAsc(slotId);
        SlotBooking latest = left.isEmpty() ? null : left.get(left.size() - 1);
        fresh.setCustomerName(latest == null ? null : latest.getCustomerName());
        fresh.setCustomerPhone(latest == null ? null : latest.getCustomerPhone());
        fresh.setService(latest == null ? null : latest.getService());
        fresh.setRecordId(latest == null ? null : latest.getRecordId());
        fresh.setBookedVia(latest == null ? null : latest.getBookedVia());
        slotRepository.save(fresh);

        return slotView(fresh, staffRepository.findByIdAndOwnerUserId(fresh.getStaffId(), own).orElse(null));
    }

    /* ------------------------------------------------------------ internals */

    /** The workspace's page, created the first time the bookings page is opened. */
    BookingPage myPage() {
        String own = owner();
        return pageRepository.findFirstByOwnerUserIdOrderByIdAsc(own).orElseGet(() -> {
            List<Map<String, Object>> forms = bookingForms();
            Long formId = forms.isEmpty() ? null : (Long) forms.get(0).get("id");
            String title = forms.isEmpty() ? "Book an appointment" : String.valueOf(forms.get(0).get("name"));
            return pageRepository.save(BookingPage.builder()
                    .ownerUserId(own)
                    .formId(formId)
                    .publicKey(newKey())
                    .enabled(false)
                    .title(title)
                    .slotMinutes(30)
                    .build());
        });
    }

    /** Forms this workspace got from a booking-capable pack and still owns, newest first. */
    List<Map<String, Object>> bookingForms() {
        String own = owner();
        Set<Long> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PackInstall install : packInstallRepository
                .findByOwnerUserIdAndPackKeyInOrderByInstalledAtDesc(own, PACK_KEYS)) {
            if (install.getFormId() == null || !seen.add(install.getFormId())) continue;
            formRepo.findById(install.getFormId())
                    .filter(form -> own.equals(form.getOwnerUserId()))
                    .ifPresent(form -> out.add(formView(form)));
        }
        return out;
    }

    private BookingStaff myStaff(Long id) {
        return staffRepository.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Person not found"));
    }

    private BookingSlot mySlot(Long id) {
        return slotRepository.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Slot not found"));
    }

    private static String newKey() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder key = new StringBuilder();
        for (byte b : bytes) key.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return key.toString();
    }

    private static String trimTo(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /* ----------------------------------------------------------------- views */

    Map<String, Object> pageView(BookingPage page) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", page.getId());
        view.put("formId", page.getFormId());
        view.put("publicKey", page.getPublicKey());
        view.put("enabled", page.isEnabled());
        view.put("title", page.getTitle());
        view.put("tagline", page.getTagline());
        view.put("note", page.getNote());
        view.put("slotMinutes", page.getSlotMinutes());
        view.put("publicUrl", publicBaseUrl + "/book/" + page.getPublicKey());
        view.put("qrUrl", apiBaseUrl + "/api/public/booking/" + page.getPublicKey() + "/qr.png");
        return view;
    }

    /**
     * Seats gone on this slot. A row written before capacity existed carries a
     * bookedCount of 0 even while BOOKED, and the backfill runs on startup —
     * reading the status as well means a restart is never needed first.
     */
    private static int seatsTaken(BookingSlot slot) {
        return Math.max(slot.getBookedCount(), slot.getStatus() == SlotStatus.BOOKED ? 1 : 0);
    }

    private Map<String, Object> staffView(BookingStaff staff) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", staff.getId());
        view.put("name", staff.getName());
        view.put("role", staff.getRole());
        view.put("active", staff.isActive());
        view.put("sortOrder", staff.getSortOrder());
        return view;
    }

    private Map<String, Object> slotView(BookingSlot slot, BookingStaff staff) {
        return slotView(slot, staff, slot.getBookedCount() == 0
                ? List.of()
                : slotBookingRepository.findBySlotIdOrderByIdAsc(slot.getId()));
    }

    private Map<String, Object> slotView(BookingSlot slot, BookingStaff staff, List<SlotBooking> bookings) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", slot.getId());
        view.put("staffId", slot.getStaffId());
        view.put("staffName", staff == null ? null : staff.getName());
        view.put("date", slot.getSlotDate().toString());
        view.put("time", slot.getStartTime().toString());
        view.put("durationMinutes", slot.getDurationMinutes());
        view.put("status", slot.getStatus().name());
        view.put("capacity", Math.max(slot.getCapacity(), 1));
        view.put("bookedCount", seatsTaken(slot));
        view.put("seatsLeft", Math.max(0, Math.max(slot.getCapacity(), 1) - seatsTaken(slot)));
        view.put("customerName", slot.getCustomerName());
        view.put("customerPhone", slot.getCustomerPhone());
        view.put("service", slot.getService());
        view.put("recordId", slot.getRecordId());
        view.put("bookedVia", slot.getBookedVia());
        // Who is actually in the slot. One name for a one-on-one, the whole
        // group where capacity is higher.
        view.put("bookings", bookings.stream().map(b -> {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", b.getId());
            one.put("customerName", b.getCustomerName());
            one.put("customerPhone", b.getCustomerPhone());
            one.put("service", b.getService());
            one.put("recordId", b.getRecordId());
            one.put("bookedVia", b.getBookedVia());
            return one;
        }).toList());
        return view;
    }

    private Map<String, Object> formView(FormEntity form) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", form.getId());
        row.put("name", form.getName());
        row.put("slug", form.getSlug());
        return row;
    }
}
