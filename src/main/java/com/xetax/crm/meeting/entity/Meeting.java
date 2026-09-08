package com.xetax.crm.meeting.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One video meeting. scheduledAt == null means an instant meeting. The room
 * is joined via roomCode + a token: guests use guestToken (goes into the
 * shared link), the owner uses hostToken. Media itself is peer-to-peer —
 * the server only relays signaling, so this stays light under load.
 */
@Entity
@Table(name = "meetings",
        uniqueConstraints = @UniqueConstraint(name = "uq_meeting_room", columnNames = "room_code"),
        indexes = @Index(name = "idx_meeting_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Meeting extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "room_code", nullable = false, length = 20)
    private String roomCode;

    @Column(name = "guest_token", nullable = false, length = 40)
    private String guestToken;

    @Column(name = "host_token", nullable = false, length = 40)
    private String hostToken;

    /** Optional link to the CRM record this meeting is about. */
    @Column(name = "record_id", length = 64)
    private String recordId;

    @Column(length = 255)
    private String guestName;

    @Column(length = 32)
    private String guestPhone;

    @Column(length = 255)
    private String guestEmail;

    private Instant scheduledAt;

    /** SCHEDULED | LIVE | ENDED | CANCELLED */
    @Column(nullable = false, length = 12)
    private String status;

    private Instant startedAt;

    private Instant endedAt;
}
