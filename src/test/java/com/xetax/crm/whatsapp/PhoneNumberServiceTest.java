package com.xetax.crm.whatsapp;

import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import com.xetax.crm.whatsapp.service.PhoneNumberService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhoneNumberServiceTest {

    private final PhoneNumberService service = new PhoneNumberService(new MetaWhatsAppProperties());

    @Test
    void tenDigitLocalGetsCountryCode() {
        assertEquals(Optional.of("919876543210"), service.normalize("9876543210"));
    }

    @Test
    void formattedInternationalIsCleaned() {
        assertEquals(Optional.of("919876543210"), service.normalize("+91 98765-43210"));
    }

    @Test
    void trunkZeroIsStripped() {
        assertEquals(Optional.of("919876543210"), service.normalize("09876543210"));
    }

    @Test
    void garbageIsRejected() {
        assertTrue(service.normalize("12345").isEmpty());
        assertTrue(service.normalize("").isEmpty());
        assertTrue(service.normalize(null).isEmpty());
        assertTrue(service.normalize("not-a-phone").isEmpty());
    }
}
