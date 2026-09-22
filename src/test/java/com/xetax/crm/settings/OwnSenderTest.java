package com.xetax.crm.settings;

import com.xetax.crm.settings.entity.OrgSmtpSettings;
import com.xetax.crm.settings.repository.OrgSmtpSettingsRepository;
import com.xetax.crm.settings.service.OrgSmtpService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Email — sending as your address" and the setup checklist both answer one
 * question: has this workspace given us a sender of its own? Counting the
 * platform's own fallback there ticked the box for owners who had set nothing,
 * and the campaign page then refused them for having no sender.
 */
class OwnSenderTest {

    private static final String OWNER = "11111111-1111-1111-1111-111111111111";

    private OrgSmtpService serviceWith(boolean ownSettings, boolean platformFallback) {
        OrgSmtpSettingsRepository repository = mock(OrgSmtpSettingsRepository.class);
        when(repository.findByOwnerUserId(OWNER))
                .thenReturn(ownSettings ? Optional.of(new OrgSmtpSettings()) : Optional.empty());

        com.xetax.crm.common.email.EmailService email = mock(com.xetax.crm.common.email.EmailService.class);
        when(email.isConfigured()).thenReturn(platformFallback);

        OrgSmtpService service = mock(OrgSmtpService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "emailService", email);
        return service;
    }

    @Test
    void aWorkspaceWithNoSmtpOfItsOwnDoesNotCountAsHavingOne() {
        assertFalse(serviceWith(false, true).hasOwnSettings(OWNER));
    }

    @Test
    void aWorkspaceThatSavedItsSmtpDoes() {
        assertTrue(serviceWith(true, false).hasOwnSettings(OWNER));
    }

    @Test
    void sendingIsStillPossibleOnThePlatformsOwnServer() {
        assertTrue(serviceWith(false, true).isConfiguredFor(OWNER));
    }

    @Test
    void withNeitherThereIsNoWayToSend() {
        assertFalse(serviceWith(false, false).isConfiguredFor(OWNER));
    }
}
