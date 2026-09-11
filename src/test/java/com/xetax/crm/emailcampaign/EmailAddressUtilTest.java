package com.xetax.crm.emailcampaign;

import com.xetax.crm.emailcampaign.util.EmailAddressUtil;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Address normalisation + CSV header detection used by the email campaign audience loaders. */
class EmailAddressUtilTest {

    @Test
    void normalizesCaseAndWhitespace() {
        assertEquals(Optional.of("rahul@example.com"), EmailAddressUtil.normalize("  Rahul@Example.COM "));
    }

    @Test
    void rejectsUnusableValues() {
        assertTrue(EmailAddressUtil.normalize(null).isEmpty());
        assertTrue(EmailAddressUtil.normalize("").isEmpty());
        assertTrue(EmailAddressUtil.normalize("not-an-email").isEmpty());
        assertTrue(EmailAddressUtil.normalize("two@@example.com").isEmpty());
        assertTrue(EmailAddressUtil.normalize("has space@example.com").isEmpty());
        assertTrue(EmailAddressUtil.normalize("nodot@example").isEmpty());
        assertTrue(EmailAddressUtil.normalize("a@b." + "x".repeat(200)).isEmpty()); // over 160 chars
    }

    @Test
    void detectsEmailHeaders() {
        assertTrue(EmailAddressUtil.isEmailHeader("email"));
        assertTrue(EmailAddressUtil.isEmailHeader("Email Address"));
        assertTrue(EmailAddressUtil.isEmailHeader("E-Mail"));
        assertTrue(EmailAddressUtil.isEmailHeader("mail"));
        assertFalse(EmailAddressUtil.isEmailHeader("phone"));
        assertFalse(EmailAddressUtil.isEmailHeader("name"));
        assertFalse(EmailAddressUtil.isEmailHeader(null));
    }
}
