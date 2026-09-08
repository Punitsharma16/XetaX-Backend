package com.xetax.crm.desk;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * "A customer wants a person." One OPEN request per session at a time; the
 * first member to accept wins (an atomic conditional update, see the repo).
 * Status: OPEN | ACCEPTED | RESOLVED | EXPIRED | DECLINED.
 */
@Entity
@Table(name = "handoff_requests",
        indexes = {
                @Index(name = "idx_handoff_owner_status", columnList = "ownerUserId,status"),
                @Index(name = "idx_handoff_session", columnList = "sessionId")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HandoffRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String ownerUserId;

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false, length = 16)
    private String channel;

    @Column(length = 160)
    private String customerLabel;

    @Column(length = 500)
    private String lastMessage;

    @Column(columnDefinition = "TEXT")
    private String aiSummary;

    @Column(length = 200)
    private String reason;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 64)
    private String acceptedBy;

    private LocalDateTime acceptedAt;

    @Column(nullable = false)
    private boolean escalated;

    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}
