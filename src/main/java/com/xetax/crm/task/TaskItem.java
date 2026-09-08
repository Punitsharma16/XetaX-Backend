package com.xetax.crm.task;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A to-do / reminder. Optionally linked to a contact or a record; unlinked =
 * personal task. Reminder fires at dueAt: panel notification always, plus
 * email and/or WhatsApp if the creator picked them.
 */
@Entity
@Table(name = "tasks", indexes = {
        @Index(name = "idx_task_assignee", columnList = "assignedTo, status"),
        @Index(name = "idx_task_contact", columnList = "contactId"),
        @Index(name = "idx_task_record", columnList = "recordId"),
        @Index(name = "idx_task_due", columnList = "status, reminderSent, dueAt")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String ownerUserId;

    @Column(nullable = false)
    private String createdBy;

    /** Who gets the reminder — defaults to the creator. */
    @Column(nullable = false)
    private String assignedTo;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String notes;

    private LocalDateTime dueAt;

    /** OPEN | DONE */
    @Column(length = 10, nullable = false)
    private String status;

    private Long contactId;
    private String recordId;
    /** Display context so lists don't need extra lookups. */
    @Column(length = 160)
    private String linkedName;

    /* Wrappers, not primitives — Jackson uses the @AllArgsConstructor as the
       creator, and an absent JSON field must not explode on a primitive. */
    private Boolean remindEmail;
    private Boolean remindWhatsApp;
    private Boolean reminderSent;

    private LocalDateTime createdAt;
    private LocalDateTime doneAt;
}
