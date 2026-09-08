package com.xetax.crm.whatsapp.dto;

import lombok.Data;

/**
 * Dev / system-user fallback: paste a long-lived token + ids directly.
 * The token is encrypted at rest exactly like the Embedded Signup token.
 */
@Data
public class ManualConnectRequest {
    private String accessToken;
    private String wabaId;
    private String phoneNumberId;
}
