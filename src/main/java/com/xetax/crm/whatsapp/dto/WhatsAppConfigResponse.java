package com.xetax.crm.whatsapp.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/** Connection state for the UI. NEVER carries the access token. */
@Getter
@Builder
public class WhatsAppConfigResponse {
    private Long id;
    private String status;
    private String wabaId;
    private String phoneNumberId;
    private String displayPhoneNumber;
    private String verifiedName;
    private String qualityRating;
    private String accountMode;
    private Instant connectedAt;
    private Instant lastSyncAt;
    private String lastError;
    private boolean webhookSubscribed;
}
