package com.xetax.crm.whatsapp.client;

/**
 * Outcome of one provider send. providerMessageId is Meta's wamid.
 * transientError=true means 429/5xx/network — worth a backoff retry;
 * false means a permanent error (bad number, unapproved template, bad token).
 */
public record WhatsAppSendResult(boolean success, String providerMessageId,
                                 String errorCode, String errorMessage,
                                 boolean transientError) {

    public static WhatsAppSendResult ok(String providerMessageId) {
        return new WhatsAppSendResult(true, providerMessageId, null, null, false);
    }

    public static WhatsAppSendResult failed(String code, String message) {
        return new WhatsAppSendResult(false, null, code, message, false);
    }

    public static WhatsAppSendResult failedTransient(String code, String message) {
        return new WhatsAppSendResult(false, null, code, message, true);
    }
}
