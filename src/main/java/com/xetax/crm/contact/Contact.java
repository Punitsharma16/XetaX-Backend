package com.xetax.crm.contact;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** A person/business in the owner's address book — deliberately few fields. */
@Entity
@Table(name = "contacts", indexes = @Index(name = "idx_contact_owner", columnList = "ownerUserId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String ownerUserId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 32)
    private String phone;

    @Column(length = 160)
    private String email;

    /** Business / company name. */
    @Column(length = 160)
    private String company;

    @Column(length = 500)
    private String address;

    @Column(length = 1000)
    private String notes;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
