package com.xetax.crm.whatsapp.dto;

import lombok.Data;

/** Result of the Meta Embedded Signup popup on the frontend. */
@Data
public class OnboardingCompleteRequest {
    /** OAuth code from FB.login — exchanged server-side, never stored. */
    private String code;
    /** Optionally reported by the signup session-info listener. */
    private String wabaId;
    private String phoneNumberId;
}
