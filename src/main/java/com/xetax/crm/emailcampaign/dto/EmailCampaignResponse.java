package com.xetax.crm.emailcampaign.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Builder
public class EmailCampaignResponse {
    private Long id;
    private String name;
    private String subject;
    private String body;
    private String sourceType;
    private String status;
    private String targetDescription;
    private int totalCount;
    private int queuedCount;
    private int sentCount;
    private int failedCount;
    private LocalDateTime createdAt;
    private Instant scheduledAt;
    private Instant startedAt;
    private Instant completedAt;
}
