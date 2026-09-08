package com.xetax.crm.meeting.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/** One person invited to a meeting — a meeting can have many. */
@Entity
@Table(name = "meeting_invitees",
        indexes = @Index(name = "idx_minv_meeting", columnList = "meeting_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MeetingInvitee extends BaseEntity {

    @Column(name = "meeting_id", nullable = false)
    private Long meetingId;

    @Column(length = 255)
    private String name;

    @Column(length = 32)
    private String phone;

    @Column(length = 255)
    private String email;
}
