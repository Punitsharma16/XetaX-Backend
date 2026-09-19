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
 * <p>The unique key on (staff, date, start) is what makes double booking
 * impossible: a slot is claimed with a conditional update that only succeeds
 * while it is still OPEN, so whoever gets there first keeps it, whether they
 * came from the public page, the WhatsApp bot or the website chat.
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

    /* ------------------------------------------------- filled once booked */

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
