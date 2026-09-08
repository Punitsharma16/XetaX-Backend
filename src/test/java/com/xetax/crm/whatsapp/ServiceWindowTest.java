package com.xetax.crm.whatsapp;

import com.xetax.crm.whatsapp.client.WhatsAppProviderException;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 24h customer-service window rule + transient-error classification. */
class ServiceWindowTest {

    private final Instant now = Instant.parse("2026-08-21T12:00:00Z");

    @Test
    void windowOpenWithin24h() {
        assertTrue(WhatsAppMessagingService.windowOpen(now.minus(1, ChronoUnit.HOURS), now));
        assertTrue(WhatsAppMessagingService.windowOpen(now.minus(23, ChronoUnit.HOURS), now));
    }

    @Test
    void windowClosedAfter24hOrNeverContacted() {
        assertFalse(WhatsAppMessagingService.windowOpen(now.minus(25, ChronoUnit.HOURS), now));
        assertFalse(WhatsAppMessagingService.windowOpen(null, now));
    }

    @Test
    void transientClassification() {
        assertTrue(new WhatsAppProviderException("META_429", "m", "d", 429).isTransient());
        assertTrue(new WhatsAppProviderException("META_503", "m", "d", 503).isTransient());
        assertTrue(new WhatsAppProviderException("NETWORK", "m", "d").isTransient()); // status 0
        assertFalse(new WhatsAppProviderException("META_400", "m", "d", 400).isTransient());
        assertFalse(new WhatsAppProviderException("META_401", "m", "d", 401).isTransient());
    }
}
