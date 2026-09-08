package com.xetax.crm.whatsapp.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Builder
public class CampaignResponse {
    private Long id;
    private String name;
    private String sourceType;
    private String status;
    private String messageTemplate;
    private String templateName;
    private String templateLanguage;
    private String targetDescription;
    private int totalCount;
    private int queuedCount;
    private int sentCount;
    private int deliveredCount;
    private int readCount;
    private int failedCount;
    private LocalDateTime createdAt;
    private Instant scheduledAt;
    private Instant startedAt;
    private Instant completedAt;
}
