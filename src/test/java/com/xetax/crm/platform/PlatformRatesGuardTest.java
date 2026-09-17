package com.xetax.crm.platform;

import com.xetax.crm.whatsapp.pricing.WhatsAppRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The rate card decides every estimate on the platform — only the XetaX team may touch it. */
class PlatformRatesGuardTest {

    private WhatsAppRateService rates;
    private PlatformController controller;

    @BeforeEach
    void setUp() {
        PlatformAdminGuard guard = mock(PlatformAdminGuard.class);
        when(guard.require()).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform administrators only"));
        rates = mock(WhatsAppRateService.class);
        controller = new PlatformController(mock(PlatformService.class), guard, rates);
    }

    @Test
    void aCustomerCannotReadTheRateCard() {
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, controller::whatsappRates).getStatusCode());
        verifyNoInteractions(rates);
    }

    @Test
    void aCustomerCannotChangeARate() {
        var input = new WhatsAppRateService.RateInput("UTILITY", new BigDecimal("9"), "2026-10-01", null);
        assertThrows(ResponseStatusException.class, () -> controller.saveWhatsappRate(input));
        verify(rates, never()).upsert(any());
    }

    @Test
    void aCustomerCannotDeleteARate() {
        assertThrows(ResponseStatusException.class, () -> controller.deleteWhatsappRate(1L));
        verify(rates, never()).delete(any());
    }
}
