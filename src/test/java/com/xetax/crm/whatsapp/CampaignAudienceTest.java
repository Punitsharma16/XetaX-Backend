package com.xetax.crm.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.data_manager.dto.RecordResponse;
import com.xetax.crm.data_manager.dto.SeacrhDto.RecordSearchRequest;
import com.xetax.crm.data_manager.repository.StageRepo;
import com.xetax.crm.data_manager.service.RecordService;
import com.xetax.crm.whatsapp.dto.CampaignCreateRequest;
import com.xetax.crm.whatsapp.entity.WhatsAppCampaign;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.enums.WhatsAppConnectionStatus;
import com.xetax.crm.whatsapp.kafka.WhatsAppEventPublisher;
import com.xetax.crm.whatsapp.repository.WhatsAppCampaignRecipientRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppCampaignRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppConfigRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppTemplateRepository;
import com.xetax.crm.whatsapp.service.PhoneNumberService;
import com.xetax.crm.whatsapp.service.WhatsAppCampaignService;
import com.xetax.crm.whatsapp.service.WhatsAppConfigService;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import com.xetax.crm.whatsapp.service.WhatsAppTemplateVariables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A campaign could only be aimed at a whole form. Wanting "the leads that
 * reached Demo Booked last week" meant exporting a CSV by hand and uploading
 * it, because neither the stage a record sits in nor the day it was created
 * can be expressed in the filter map — that one is keyed by form fieldKey and
 * reaches only inside a record's data.
 */
class CampaignAudienceTest {

    private RecordService recordService;
    private WhatsAppCampaignRepository campaigns;
    private WhatsAppCampaignService service;

    @BeforeEach
    void setUp() {
        recordService = mock(RecordService.class);
        campaigns = mock(WhatsAppCampaignRepository.class);
        WhatsAppCampaignRecipientRepository recipients =
                mock(WhatsAppCampaignRecipientRepository.class);
        PhoneNumberService phoneNumberService = mock(PhoneNumberService.class);
        WhatsAppConfigService configService = mock(WhatsAppConfigService.class);

        WhatsAppConfig config = new WhatsAppConfig();
        config.setId(1L);
        config.setOwnerUserId("11111111-1111-1111-1111-111111111111");
        config.setStatus(WhatsAppConnectionStatus.CONNECTED);
        when(configService.requireConnectedConfig()).thenReturn(config);

        when(campaigns.save(any())).thenAnswer(call -> {
            WhatsAppCampaign saved = call.getArgument(0);
            saved.setId(6L);
            return saved;
        });
        when(recipients.save(any())).thenAnswer(call -> call.getArgument(0));
        when(phoneNumberService.normalize(anyString()))
                .thenAnswer(call -> Optional.of(call.getArgument(0)));

        RecordResponse record = RecordResponse.builder()
                .id("rec-1")
                .formId(5L)
                .data(Map.of("mobile", "919812345678"))
                .build();
        when(recordService.search(anyString(), any(RecordSearchRequest.class)))
                .thenReturn(new PageImpl<>(List.of(record), PageRequest.of(0, 200), 1));

        @SuppressWarnings("unchecked")
        ObjectProvider<WhatsAppCampaignService> self = mock(ObjectProvider.class);

        service = new WhatsAppCampaignService(campaigns, recipients,
                mock(WhatsAppConfigRepository.class), configService,
                mock(WhatsAppMessagingService.class), phoneNumberService, recordService,
                mock(WhatsAppEventPublisher.class), new ObjectMapper(),
                mock(KnowledgeIndexer.class), mock(WhatsAppTemplateRepository.class),
                mock(WhatsAppTemplateVariables.class), mock(StageRepo.class), self);
    }

    private CampaignCreateRequest draft() {
        CampaignCreateRequest request = new CampaignCreateRequest();
        request.setName("Diwali offer");
        request.setSourceType("RECORDS");
        request.setMessageTemplate("Hello");
        request.setFormSlug("leads");
        request.setPhoneFieldKey("mobile");
        return request;
    }

    /** The search the campaign actually ran against the records. */
    private RecordSearchRequest capturedSearch() {
        ArgumentCaptor<RecordSearchRequest> captor =
                ArgumentCaptor.forClass(RecordSearchRequest.class);
        verify(recordService, atLeastOnce()).search(anyString(), captor.capture());
        return captor.getValue();
    }

    /** The audience line shown under the campaign's name. */
    private String savedTargetDescription() {
        ArgumentCaptor<WhatsAppCampaign> captor = ArgumentCaptor.forClass(WhatsAppCampaign.class);
        verify(campaigns, atLeastOnce()).save(captor.capture());
        return captor.getValue().getTargetDescription();
    }

    // ------------------------------------------------------------ unchanged

    @Test
    void aCampaignOnTheWholeFormNarrowsNothing() {
        service.create(draft());

        RecordSearchRequest search = capturedSearch();
        assertNull(search.getStageId());
        assertNull(search.getCreatedFrom());
        assertNull(search.getCreatedTo());
    }

    // ---------------------------------------------------------- the new bits

    @Test
    void aStageReachesTheRecordSearch() {
        CampaignCreateRequest request = draft();
        request.setStageId(9L);

        service.create(request);

        assertEquals(9L, capturedSearch().getStageId());
    }

    @Test
    void aDateRangeReachesTheRecordSearch() {
        CampaignCreateRequest request = draft();
        request.setCreatedFrom(LocalDate.of(2026, 9, 1));
        request.setCreatedTo(LocalDate.of(2026, 9, 30));

        service.create(request);

        RecordSearchRequest search = capturedSearch();
        assertEquals(LocalDate.of(2026, 9, 1), search.getCreatedFrom());
        assertEquals(LocalDate.of(2026, 9, 30), search.getCreatedTo());
    }

    @Test
    void everyPageOfTheAudienceCarriesTheSameNarrowing() {
        CampaignCreateRequest request = draft();
        request.setStageId(9L);
        request.setCreatedFrom(LocalDate.of(2026, 9, 1));

        service.create(request);

        // The loader pages through the records; a page that dropped the filter
        // would quietly pull in everybody else.
        ArgumentCaptor<RecordSearchRequest> captor =
                ArgumentCaptor.forClass(RecordSearchRequest.class);
        verify(recordService, atLeastOnce()).search(anyString(), captor.capture());
        for (RecordSearchRequest search : captor.getAllValues()) {
            assertEquals(9L, search.getStageId());
            assertEquals(LocalDate.of(2026, 9, 1), search.getCreatedFrom());
        }
    }

    // ------------------------------------------------------- what it says

    @Test
    void theCampaignPageSpellsOutHowTheAudienceWasNarrowed() {
        CampaignCreateRequest request = draft();
        request.setStageId(9L);
        request.setCreatedFrom(LocalDate.of(2026, 9, 1));
        request.setCreatedTo(LocalDate.of(2026, 9, 30));

        service.create(request);

        // 40 recipients out of a 4,000-record form otherwise looks like a form
        // that only has 40 records in it.
        String description = savedTargetDescription();
        assertTrue(description.contains("stage"), description);
        assertTrue(description.contains("2026-09-01"), description);
        assertTrue(description.contains("2026-09-30"), description);
    }

    @Test
    void anUnnarrowedCampaignStillReadsTheWayItDid() {
        service.create(draft());

        assertEquals("Records of form 'leads'", savedTargetDescription());
    }
}
