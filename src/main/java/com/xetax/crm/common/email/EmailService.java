package com.xetax.crm.common.email;

public interface EmailService {

    void send(String to, String subject, String body);

    /** True when SMTP is actually set up — callers can fail loudly instead of silently logging. */
    default boolean isConfigured() {
        return false;
    }

    /** Owner-aware send: us org ki panel-configured SMTP se, warna global .env fallback. */
    default void send(String ownerUserId, String to, String subject, String body) {
        send(to, subject, body);
    }

    default boolean isConfigured(String ownerUserId) {
        return isConfigured();
    }

}
