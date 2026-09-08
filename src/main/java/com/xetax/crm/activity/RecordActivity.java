package com.xetax.crm.activity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One line of a record's history — who did what, when. Append-only; written
 * as a side effect of the record operations and never in their transaction
 * path (a failed log line must not fail the operation it describes).
 */
@Entity
@Table(name = "record_activities", indexes = {
        @Index(name = "idx_activity_record", columnList = "recordId, createdAt")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecordActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Org (data owner) — scoping guard, mirrors the record's form owner. */
    @Column(nullable = false)
    private String ownerUserId;

    /** Mongo id of the record this line belongs to. */
    @Column(nullable = false, length = 64)
    private String recordId;

    /** Who performed the action; null for system/automation writes. */
    @Column(length = 64)
    private String actorId;

    @Column(length = 160)
    private String actorName;

    /** CREATED | UPDATED | STAGE_CHANGED | STATUS_CHANGED | ASSIGNED | DOCUMENT_SENT | WHATSAPP_SENT */
    @Column(length = 32, nullable = false)
    private String type;

    @Column(length = 500)
    private String detail;

    private LocalDateTime createdAt;
}
