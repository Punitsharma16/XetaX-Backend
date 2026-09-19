package com.xetax.crm.booking.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * One workspace's public booking page — the single place a customer sees
 * available slots and confirms one.
 *
 * <p>Nothing about the salon itself is a record. Only a confirmed booking
 * reaches the CRM, as a record in {@link #formId} — the form the Hair Salon
 * pack installed — so the pipeline carries real appointments and nothing else.
 */
@Entity
@Table(name = "booking_pages",
        uniqueConstraints = @UniqueConstraint(name = "uq_booking_page_key", columnNames = {"public_key"}),
        indexes = @Index(name = "idx_booking_page_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingPage extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    /** The Hair Salon pack's form every booking is created in. */
    @Column(name = "form_id")
    private Long formId;

    /** The only credential the public page has; a new one kills old links and QR codes. */
    @Column(name = "public_key", length = 40, nullable = false)
    private String publicKey;

    /** Off until the owner turns it on — a page with no slots is never public. */
    @Column(nullable = false)
    private boolean enabled;

    @Column(length = 120)
    private String title;

    @Column(length = 255)
    private String tagline;

    /** Shown under the slot list: parking, cancellation, anything they want said. */
    @Column(length = 500)
    private String note;

    /** Default length of a slot when the owner adds a day's slots. */
    @Column(name = "slot_minutes", nullable = false)
    private int slotMinutes;
}
