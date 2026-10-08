package com.xetax.crm.booking.entity;

import com.xetax.crm.booking.enums.SlotStatus;
import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One bookable slot of one staff member — the only thing a customer can pick.
 *
 * <p>The unique key on (staff, date, start) plus a conditional update is what
 * makes overbooking impossible: a seat is claimed only while bookedCount is
 * still below capacity, so whoever gets there first keeps it, whether they
 * came from the public page, the WhatsApp bot or the website chat.
 *
 * <p>capacity is 1 by default — one person, one slot, which is how a chair, a
 * doctor's OPD turn and a technician's visit all work. A workspace that runs
 * group sessions (a demo class, a batch) raises it, and the same slot then
 * takes that many bookings before it closes.
 */
@Entity
@Table(name = "booking_slots",
        uniqueConstraints = @UniqueConstraint(name = "uq_booking_slot",
                columnNames = {"staff_id", "slot_date", "start_time"}),
        indexes = {
                @Index(name = "idx_booking_slot_owner_date", columnList = "owner_user_id, slot_date"),
                @Index(name = "idx_booking_slot_status", columnList = "owner_user_id, status, slot_date")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingSlot extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    @Column(name = "slot_date", nullable = false)
    private LocalDate slotDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    /** Minutes — what the customer is told, and what the next slot starts after. */
    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SlotStatus status;

    /**
     * How many people this slot takes. 1 means one-on-one.
     *
     * <p>columnDefinition carries the default into the DDL as well: without it
     * the column added to an existing table lands as 0 on every row already
     * there, and a slot with no seats can never be booked again.
     */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "int not null default 1")
    private int capacity = 1;

    /** How many of those seats are gone. Moved only by the atomic claim/release. */
    @Builder.Default
    @Column(name = "booked_count", nullable = false, columnDefinition = "int not null default 0")
    private int bookedCount = 0;

    /* ------------------------------------------------- filled once booked */

    /*
     * For a one-on-one slot these are the booking. Where capacity is more than
     * one they mirror the latest booking, and the full list lives in
     * SlotBooking — so everything that read a slot before capacity existed
     * still reads the same thing.
     */

    /** The CRM record this booking became — the booking's home in the pipeline. */
    @Column(name = "record_id", length = 64)
    private String recordId;

    @Column(name = "customer_name", length = 160)
    private String customerName;

    @Column(name = "customer_phone", length = 32)
    private String customerPhone;

    @Column(length = 160)
    private String service;

    /** Where the booking came from: PAGE, BOT or PANEL — useful in support. */
    @Column(name = "booked_via", length = 12)
    private String bookedVia;
}
