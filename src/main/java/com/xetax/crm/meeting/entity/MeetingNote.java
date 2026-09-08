package com.xetax.crm.meeting.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/** A timestamped note taken alongside a meeting — the "minutes" trail. */
@Entity
@Table(name = "meeting_notes",
        indexes = @Index(name = "idx_mnote_meeting", columnList = "meeting_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MeetingNote extends BaseEntity {

    @Column(name = "meeting_id", nullable = false)
    private Long meetingId;

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(nullable = false, length = 4000)
    private String content;
}
