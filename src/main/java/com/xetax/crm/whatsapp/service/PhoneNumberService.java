package com.xetax.crm.whatsapp.service;

import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Normalizes phone input to E.164-without-plus digits, which is what the
 * Meta Cloud API expects. 10-digit local numbers get the configured default
 * country code. Returns empty for anything unusable.
 */
@Service
@RequiredArgsConstructor
public class PhoneNumberService {

    private final MetaWhatsAppProperties properties;

    public Optional<String> normalize(String raw) {
        if (raw == null) return Optional.empty();
        String digits = raw.replaceAll("[^0-9]", "");
        // "0" trunk prefix on an 11-digit local number → strip it
        if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (digits.length() == 10) {
            digits = properties.getDefaultCountryCode() + digits;
        }
        if (digits.length() < 11 || digits.length() > 15) {
            return Optional.empty();
        }
        return Optional.of(digits);
    }
}
