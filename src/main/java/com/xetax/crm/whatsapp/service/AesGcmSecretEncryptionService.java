package com.xetax.crm.whatsapp.service;

import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM. Key material comes from WHATSAPP_TOKEN_ENCRYPTION_KEY (any
 * string — SHA-256 derives the 32-byte key). Output = base64(iv || ciphertext),
 * fresh random IV per encryption. Decrypt failures throw — callers treat the
 * config as broken instead of using a garbage token.
 */
@Slf4j
@Service
public class AesGcmSecretEncryptionService implements SecretEncryptionService {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmSecretEncryptionService(MetaWhatsAppProperties properties) {
        String material = properties.getTokenEncryptionKey();
        if (material == null || material.isBlank()) {
            log.warn("WHATSAPP_TOKEN_ENCRYPTION_KEY is not set — using an insecure dev fallback. "
                    + "Set it before connecting a real WhatsApp account.");
            material = "xetax-dev-only-not-for-production";
        }
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(derived, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialise token encryption", e);
        }
    }

    @Override
    public String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherBytes, 0, out, iv.length, cipherBytes.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Token encryption failed", e);
        }
    }

    @Override
    public String decrypt(String cipherText) {
        if (cipherText == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Token decryption failed — check WHATSAPP_TOKEN_ENCRYPTION_KEY", e);
        }
    }
}
