package com.xetax.crm.whatsapp.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Builder
public class WhatsAppMessageResponse {
    private Long id;
    private Long conversationId;
    private String direction;
    private String messageType;
    private String body;
    private String templateName;
    private String toPhone;
    private String status;
    private String errorMessage;
    private String recordId;
    private Long campaignId;
    private LocalDateTime createdAt;
    private Instant sentAt;
    private Instant deliveredAt;
    private Instant readAt;
}
