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

    /**
     * Meta's messaging tier, e.g. TIER_250 / TIER_1K — how many unique
     * customers this number may message in a day. It was fetched at onboarding
     * and stored all along, but never sent to the panel, so the one number
     * that decides whether a campaign can actually go out was invisible.
     */
    private String messagingLimit;
    private Instant connectedAt;
    private Instant lastSyncAt;
    private String lastError;
    private boolean webhookSubscribed;
}
