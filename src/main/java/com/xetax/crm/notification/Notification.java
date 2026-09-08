package com.xetax.crm.notification;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One panel notification for one user (the bell). Pushed by: inbound WhatsApp,
 * new records (public form / webhook / member / automation), task reminders
 * and the daily autopilot digest.
 */
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notif_target", columnList = "targetUserId, readAt")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Org (data owner) — scoping/cleanup. */
    @Column(nullable = false)
    private String ownerUserId;

    /** The user whose bell shows this. */
    @Column(nullable = false)
    private String targetUserId;

    /** WHATSAPP_INBOUND | RECORD_CREATED | TASK_DUE | DIGEST | SYSTEM */
    @Column(length = 32, nullable = false)
    private String type;

    @Column(length = 200, nullable = false)
    private String title;

    @Column(length = 1000)
    private String body;

    /** Frontend route to open on click (e.g. /app/records/leads/abc). */
    @Column(length = 300)
    private String link;

    private LocalDateTime readAt;

    private LocalDateTime createdAt;
}
