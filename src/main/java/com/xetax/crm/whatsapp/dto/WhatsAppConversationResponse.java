package com.xetax.crm.whatsapp.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class WhatsAppConversationResponse {
    private Long id;
    private String customerPhone;
    private String customerName;
    private String lastMessage;
    private Instant lastMessageAt;
    private int unreadCount;
    private String status;
    private String recordId;
    private Instant lastInboundAt;
    /** True while free-form text is allowed (24h since last customer message). */
    private boolean windowOpen;
    private Instant windowExpiresAt;
}
