package com.xetax.crm.booking.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * One person customers can book: a stylist, a barber, a therapist. The salon
 * owner adds their own people here, and every slot belongs to exactly one of
 * them — that is what keeps two customers out of the same chair.
 */
@Entity
@Table(name = "booking_staff",
        indexes = @Index(name = "idx_booking_staff_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingStaff extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(length = 120, nullable = false)
    private String name;

    /** What they do — shown to the customer under the name ("Senior stylist"). */
    @Column(length = 160)
    private String role;

    /** Taken off the public page without deleting their history. */
    @Column(nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
