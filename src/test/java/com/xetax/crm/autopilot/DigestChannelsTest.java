package com.xetax.crm.autopilot;

import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import com.xetax.crm.data_manager.repository.StageRepo;
import com.xetax.crm.notification.NotificationService;
import com.xetax.crm.settings.repository.DigestPreferenceRepository;
import com.xetax.crm.settings.service.DigestPreferenceService;
import com.xetax.crm.settings.service.OrgSmtpService;
import com.xetax.crm.task.TaskRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppConversationRepository;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The morning digest reaches exactly the channels the owner left on. The bell
 * has no switch of its own: it follows the digest as a whole.
 */
class DigestChannelsTest {

    private static final String OWNER = UUID.randomUUID().toString();

    private FormRepo formRepo;
    private NotificationService notifications;
    private OrgSmtpService smtp;
    private WhatsAppMessagingService whatsapp;
    private DigestPreferenceService preferences;
    private AutopilotService autopilot;

    @BeforeEach
    void setUp() {
        formRepo = mock(FormRepo.class);
        notifications = mock(NotificationService.class);
        smtp = mock(OrgSmtpService.class);
        whatsapp = mock(WhatsAppMessagingService.class);
        preferences = mock(DigestPreferenceService.class);
        AuthUserRepository users = mock(AuthUserRepository.class);

        AuthUserEntity owner = mock(AuthUserEntity.class);
        when(owner.getEmail()).thenReturn("owner@example.com");
        when(owner.getPhone()).thenReturn("9876543210");
        when(users.findById(any(UUID.class))).thenReturn(Optional.of(owner));
        when(smtp.isConfiguredFor(OWNER)).thenReturn(true);

        autopilot = new AutopilotService(formRepo, mock(StageRepo.class), mock(RecordRepo.class),
                mock(WhatsAppConversationRepository.class), mock(TaskRepository.class),
                notifications, smtp, whatsapp, users, preferences);
    }

    private void channels(boolean enabled, boolean email, boolean wa) {
        when(preferences.forOwner(OWNER)).thenReturn(new DigestPreferenceService.Channels(enabled, email, wa));
    }

    @Test
    void everythingOnReachesBellEmailAndWhatsApp() {
        channels(true, true, true);
        autopilot.runFor(OWNER);
        verify(notifications).push(eq(OWNER), eq(OWNER), eq("DIGEST"), anyString(), anyString(), anyString());
        verify(smtp).sendAs(eq(OWNER), eq("owner@example.com"), anyString(), anyString());
        verify(whatsapp).sendTextAsOwner(eq(OWNER), eq("9876543210"), anyString());
    }

    @Test
    void emailOffSkipsOnlyEmail() {
        channels(true, false, true);
        autopilot.runFor(OWNER);
        verify(notifications).push(eq(OWNER), eq(OWNER), eq("DIGEST"), anyString(), anyString(), anyString());
        verify(smtp, never()).sendAs(anyString(), anyString(), anyString(), anyString());
        verify(whatsapp).sendTextAsOwner(eq(OWNER), anyString(), anyString());
    }

    @Test
    void whatsappOffSkipsOnlyWhatsApp() {
        channels(true, true, false);
        autopilot.runFor(OWNER);
        verify(notifications).push(eq(OWNER), eq(OWNER), eq("DIGEST"), anyString(), anyString(), anyString());
        verify(smtp).sendAs(eq(OWNER), anyString(), anyString(), anyString());
        verify(whatsapp, never()).sendTextAsOwner(anyString(), anyString(), anyString());
    }

    @Test
    void emailAndWhatsAppOffStillRingTheBell() {
        channels(true, false, false);
        autopilot.runFor(OWNER);
        verify(notifications).push(eq(OWNER), eq(OWNER), eq("DIGEST"), anyString(), anyString(), anyString());
        verify(smtp, never()).sendAs(anyString(), anyString(), anyString(), anyString());
        verify(whatsapp, never()).sendTextAsOwner(anyString(), anyString(), anyString());
    }

    @Test
    void digestOffDeliversNothingAnywhere() {
        channels(false, true, true);
        String text = autopilot.runFor(OWNER);
        assertNotNull(text, "run-now still gets the text back");
        verify(notifications, never()).push(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(smtp, never()).sendAs(anyString(), anyString(), anyString(), anyString());
        verify(whatsapp, never()).sendTextAsOwner(anyString(), anyString(), anyString());
    }

    @Test
    void theNineOClockRunSkipsOwnersWhoTurnedItOff() {
        FormEntity form = mock(FormEntity.class);
        when(form.getOwnerUserId()).thenReturn(OWNER);
        when(formRepo.findAll()).thenReturn(List.of(form));
        channels(false, true, true);

        autopilot.morningDigests();

        verify(formRepo, never()).findByOwnerUserId(anyString());
        verify(notifications, never()).push(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anOwnerWhoNeverSavedGetsEverythingOn() {
        DigestPreferenceRepository repository = mock(DigestPreferenceRepository.class);
        when(repository.findByOwnerUserId(OWNER)).thenReturn(Optional.empty());
        DigestPreferenceService real = new DigestPreferenceService(repository, null, null, null, null);

        DigestPreferenceService.Channels c = real.forOwner(OWNER);
        assertTrue(c.enabled());
        assertTrue(c.emailEnabled());
        assertTrue(c.whatsappEnabled());
    }
}
