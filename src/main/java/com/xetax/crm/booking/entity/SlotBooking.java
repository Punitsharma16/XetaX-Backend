package com.xetax.crm.booking.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One person's seat in a slot.
 *
 * <p>A one-on-one slot has exactly one of these and the slot's own customer
 * fields say the same thing. A group slot — a demo class, a batch — has one
 * row per attendee, and this is the only place all of them are listed.
 */
@Entity
@Table(name = "slot_bookings",
        indexes = {
                @Index(name = "idx_slot_booking_slot", columnList = "slot_id"),
                @Index(name = "idx_slot_booking_owner", columnList = "owner_user_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SlotBooking extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(name = "slot_id", nullable = false)
    private Long slotId;

    @Column(name = "customer_name", length = 160, nullable = false)
    private String customerName;

    @Column(name = "customer_phone", length = 32, nullable = false)
    private String customerPhone;

    @Column(length = 160)
    private String service;

    /** The CRM record this booking became. */
    @Column(name = "record_id", length = 64)
    private String recordId;

    /** PAGE, BOT or PANEL. */
    @Column(name = "booked_via", length = 12)
    private String bookedVia;
}
