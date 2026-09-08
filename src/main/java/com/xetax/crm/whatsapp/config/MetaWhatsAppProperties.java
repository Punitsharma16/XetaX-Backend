package com.xetax.crm.whatsapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * All Meta / WhatsApp settings come from configuration — NOTHING is hardcoded.
 * Real values live in environment variables; application.yaml only has
 * ${PLACEHOLDER:} defaults so no secret ever reaches source control.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "meta.whatsapp")
public class MetaWhatsAppProperties {

    /** e.g. v23.0 — configurable because Meta versions rotate. Never hardcode. */
    private String graphApiVersion = "v23.0";

    private String graphBaseUrl = "https://graph.facebook.com";

    private String appId = "";

    /** App secret — used for OAuth code exchange and webhook signature check. */
    private String appSecret = "";

    /** Embedded Signup configuration id (from Meta App Dashboard → WhatsApp). */
    private String configId = "";

    /** Must match the token typed into the Meta webhook setup screen. */
    private String webhookVerifyToken = "";

    /** AES key material for encrypting stored access tokens. */
    private String tokenEncryptionKey = "";

    /** Prepended to 10-digit local numbers, e.g. 91. */
    private String defaultCountryCode = "91";

    /** Per-user outbound sends per minute (RateLimiterService window). */
    private int sendsPerMinute = 60;

    private int apiConnectTimeoutMs = 5000;

    private int apiReadTimeoutMs = 15000;

    /** Extra attempts after the first send for transient (429/5xx/network) errors. */
    private int apiMaxRetries = 2;

    public String apiUrl(String path) {
        return graphBaseUrl + "/" + graphApiVersion + path;
    }
}
