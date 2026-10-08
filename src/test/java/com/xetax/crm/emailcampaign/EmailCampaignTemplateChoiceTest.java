package com.xetax.crm.emailcampaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.contact.ContactRepository;
import com.xetax.crm.data_manager.service.RecordService;
import com.xetax.crm.emailcampaign.config.EmailCampaignProperties;
import com.xetax.crm.emailcampaign.dto.EmailCampaignCreateRequest;
import com.xetax.crm.emailcampaign.entity.EmailCampaign;
import com.xetax.crm.emailcampaign.entity.EmailTemplate;
import com.xetax.crm.emailcampaign.kafka.EmailCampaignEventPublisher;
import com.xetax.crm.emailcampaign.repository.EmailCampaignRecipientRepository;
import com.xetax.crm.emailcampaign.repository.EmailCampaignRepository;
import com.xetax.crm.emailcampaign.service.EmailCampaignService;
import com.xetax.crm.emailcampaign.service.EmailTemplateService;
import com.xetax.crm.settings.entity.OrgSmtpSettings;
import com.xetax.crm.settings.repository.OrgSmtpSettingsRepository;
import com.xetax.crm.settings.service.OrgSmtpService;
import com.xetax.crm.common.ratelimit.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The campaign wizard can start from a saved template or from text typed on
 * the spot, and the two must not get in each other's way: a template only
 * pre-fills, so anything typed wins, and a campaign with no templateId behaves
 * exactly as it did before templates existed.
 */
class EmailCampaignTemplateChoiceTest {

    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String OWNER_ID = OWNER.toString();

    private EmailCampaignRepository campaigns;
    private EmailTemplateService templates;
    private EmailCampaignService service;

    @BeforeEach
    void setUp() {
        campaigns = mock(EmailCampaignRepository.class);
        templates = mock(EmailTemplateService.class);

        EmailCampaignRecipientRepository recipients = mock(EmailCampaignRecipientRepository.class);
        OrgSmtpSettingsRepository smtpRepo = mock(OrgSmtpSettingsRepository.class);
        CurrentUserProvider users = mock(CurrentUserProvider.class);

        when(users.currentDataOwnerIdOrNull()).thenReturn(OWNER);
        // A sender must exist or create() stops before it reaches the text.
        when(smtpRepo.findByOwnerUserId(OWNER_ID)).thenReturn(Optional.of(new OrgSmtpSettings()));
        when(campaigns.save(any(EmailCampaign.class))).thenAnswer(i -> i.getArgument(0));
        when(templates.findForCampaign(any(), anyString())).thenReturn(Optional.empty());

        ContactRepository contacts = mock(ContactRepository.class);

        service = new EmailCampaignService(
                campaigns, recipients, smtpRepo, mock(OrgSmtpService.class), contacts,
                mock(RecordService.class), users, mock(RateLimiterService.class),
                mock(EmailCampaignEventPublisher.class), new EmailCampaignProperties(),
                new ObjectMapper(), mock(KnowledgeIndexer.class), templates);
    }

    private static EmailCampaignCreateRequest req() {
        EmailCampaignCreateRequest r = new EmailCampaignCreateRequest();
        r.setName("October push");
        // CSV attaches its recipients later, so these stay about the text, not the audience.
        r.setSourceType("CSV");
        return r;
    }

    private static EmailTemplate template() {
        return EmailTemplate.builder()
                .ownerUserId(OWNER_ID).name("Diwali").subject("Template subject").body("Template body")
                .build();
    }

    private EmailCampaign saved() {
        ArgumentCaptor<EmailCampaign> captor = ArgumentCaptor.forClass(EmailCampaign.class);
        verify(campaigns, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------- typing it on the spot

    @Test
    void typedSubjectAndBodyStillWorkWithNoTemplate() {
        EmailCampaignCreateRequest r = req();
        r.setSubject("Typed subject");
        r.setBody("Typed body");

        service.create(r);

        assertEquals("Typed subject", saved().getSubject());
        assertEquals("Typed body", saved().getBody());
    }

    @Test
    void stillRefusesACampaignWithNoSubject() {
        EmailCampaignCreateRequest r = req();
        r.setBody("Typed body");

        assertThrows(BadRequestException.class, () -> service.create(r));
    }

    @Test
    void stillRefusesACampaignWithNoBody() {
        EmailCampaignCreateRequest r = req();
        r.setSubject("Typed subject");

        assertThrows(BadRequestException.class, () -> service.create(r));
    }

    // ------------------------------------------------- choosing a template

    @Test
    void aChosenTemplateFillsTheSubjectAndBody() {
        when(templates.findForCampaign(5L, OWNER_ID)).thenReturn(Optional.of(template()));
        EmailCampaignCreateRequest r = req();
        r.setTemplateId(5L);

        service.create(r);

        assertEquals("Template subject", saved().getSubject());
        assertEquals("Template body", saved().getBody());
    }

    @Test
    void textTypedOverATemplateWins() {
        when(templates.findForCampaign(5L, OWNER_ID)).thenReturn(Optional.of(template()));
        EmailCampaignCreateRequest r = req();
        r.setTemplateId(5L);
        r.setSubject("Edited subject");
        r.setBody("Edited body");

        service.create(r);

        assertEquals("Edited subject", saved().getSubject());
        assertEquals("Edited body", saved().getBody());
    }

    @Test
    void oneFieldCanBeEditedWhileTheOtherComesFromTheTemplate() {
        when(templates.findForCampaign(5L, OWNER_ID)).thenReturn(Optional.of(template()));
        EmailCampaignCreateRequest r = req();
        r.setTemplateId(5L);
        r.setSubject("Edited subject");

        service.create(r);

        assertEquals("Edited subject", saved().getSubject());
        assertEquals("Template body", saved().getBody());
    }

    @Test
    void aTemplateIdThatIsNotTheCallersIsRefused() {
        when(templates.findForCampaign(5L, OWNER_ID)).thenReturn(Optional.empty());
        EmailCampaignCreateRequest r = req();
        r.setTemplateId(5L);

        assertThrows(BadRequestException.class, () -> service.create(r));
    }

    @Test
    void theTemplateIsLookedUpAgainstTheCallersOwnWorkspace() {
        when(templates.findForCampaign(5L, OWNER_ID)).thenReturn(Optional.of(template()));
        EmailCampaignCreateRequest r = req();
        r.setTemplateId(5L);

        service.create(r);

        verify(templates).findForCampaign(5L, OWNER_ID);
    }
}
