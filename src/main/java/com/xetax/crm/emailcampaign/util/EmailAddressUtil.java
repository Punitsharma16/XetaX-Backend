package com.xetax.crm.emailcampaign.util;

import java.util.Optional;
import java.util.regex.Pattern;

/** Email counterpart of PhoneNumberService.normalize — pure, static, unit-tested. */
public final class EmailAddressUtil {

    private static final int MAX_LENGTH = 160;

    /** Deliberately loose: one @, a dot in the domain, no whitespace. Providers do the rest. */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    private EmailAddressUtil() {}

    /** Trimmed, lower-cased address; empty when the value is not a usable email. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) return Optional.empty();
        String value = raw.trim().toLowerCase();
        if (value.isEmpty() || value.length() > MAX_LENGTH || !EMAIL.matcher(value).matches()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /** True for a CSV header that should be read as the email column. */
    public static boolean isEmailHeader(String header) {
        if (header == null) return false;
        String name = header.trim().toLowerCase();
        return name.equals("email") || name.equals("e-mail") || name.equals("mail")
                || name.contains("email") || name.contains("e-mail");
    }
}
