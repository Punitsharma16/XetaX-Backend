package com.xetax.crm.whatsapp;

import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import com.xetax.crm.whatsapp.service.AesGcmSecretEncryptionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretEncryptionServiceTest {

    private AesGcmSecretEncryptionService serviceWithKey(String key) {
        MetaWhatsAppProperties properties = new MetaWhatsAppProperties();
        properties.setTokenEncryptionKey(key);
        return new AesGcmSecretEncryptionService(properties);
    }

    @Test
    void roundTripRestoresPlaintext() {
        AesGcmSecretEncryptionService service = serviceWithKey("test-key-material");
        String token = "EAAG-super-secret-token-123";
        String encrypted = service.encrypt(token);
        assertNotEquals(token, encrypted);
        assertEquals(token, service.decrypt(encrypted));
    }

    @Test
    void randomIvMakesCiphertextsDiffer() {
        AesGcmSecretEncryptionService service = serviceWithKey("test-key-material");
        assertNotEquals(service.encrypt("same"), service.encrypt("same"));
    }

    @Test
    void wrongKeyFailsLoudly() {
        String encrypted = serviceWithKey("key-one").encrypt("secret");
        AesGcmSecretEncryptionService other = serviceWithKey("key-two");
        assertThrows(IllegalStateException.class, () -> other.decrypt(encrypted));
    }
}
