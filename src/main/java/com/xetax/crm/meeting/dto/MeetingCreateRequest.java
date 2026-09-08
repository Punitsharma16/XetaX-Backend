package com.xetax.crm.meeting.dto;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class MeetingCreateRequest {
    private String title;
    /** null/absent = instant meeting */
    private Instant scheduledAt;
    private String recordId;
    private String guestName;
    private String guestPhone;
    private String guestEmail;

    /** Any number of people to invite; guestName/phone/email (if set) becomes the first. */
    private List<InviteeRequest> invitees = new ArrayList<>();

    @lombok.Data
    public static class InviteeRequest {
        private String name;
        private String phone;
        private String email;
    }
}
