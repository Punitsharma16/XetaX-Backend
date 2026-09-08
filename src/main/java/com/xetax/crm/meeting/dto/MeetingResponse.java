package com.xetax.crm.meeting.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class MeetingResponse {
    private Long id;
    private String title;
    private String roomCode;
    private String status;
    private Instant scheduledAt;
    private Instant startedAt;
    private Instant endedAt;
    private String recordId;
    private String guestName;
    private String guestPhone;
    private String guestEmail;
    /** Shareable guest link (guest token). */
    private String guestLink;
    /** Owner's own join link (host token). */
    private String hostLink;
    private LocalDateTime createdAt;
    private int noteCount;
    private List<InviteeResponse> invitees;

    @Getter
    @Builder
    public static class InviteeResponse {
        private Long id;
        private String name;
        private String phone;
        private String email;
    }
}
