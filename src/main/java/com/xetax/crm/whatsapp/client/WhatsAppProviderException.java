package com.xetax.crm.whatsapp.client;

import lombok.Getter;

/**
 * A Meta Graph API failure, already stripped of anything secret. userMessage
 * is safe for API responses; details (never containing tokens) go to logs only.
 */
@Getter
public class WhatsAppProviderException extends RuntimeException {

    private final String errorCode;
    private final String userMessage;
    /** HTTP status from Meta (0 for network failures) — drives retry decisions. */
    private final int httpStatus;

    public WhatsAppProviderException(String errorCode, String userMessage, String logDetail) {
        this(errorCode, userMessage, logDetail, 0);
    }

    public WhatsAppProviderException(String errorCode, String userMessage, String logDetail,
                                     int httpStatus) {
        super(logDetail);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
        this.httpStatus = httpStatus;
    }

    /** 429, any 5xx and network failures are transient — safe to retry with backoff. */
    public boolean isTransient() {
        return httpStatus == 0 || httpStatus == 429 || httpStatus >= 500;
    }
}
