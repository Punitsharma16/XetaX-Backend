package com.xetax.crm.meeting.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class MeetingNoteResponse {
    private Long id;
    private String content;
    private LocalDateTime createdAt;
}
