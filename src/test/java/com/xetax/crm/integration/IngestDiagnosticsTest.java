package com.xetax.crm.integration;

import com.xetax.crm.common.exception.IntegrationInactiveException;
import com.xetax.crm.data_manager.dto.RecordRequest;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.service.RecordService;
import com.xetax.crm.integration.entity.Integration;
import com.xetax.crm.integration.enums.IntegrationStatus;
import com.xetax.crm.integration.enums.IntegrationType;
import com.xetax.crm.integration.repository.IntegrationRepository;
import com.xetax.crm.integration.service.IntegrationIngestServiceImpl;
import com.xetax.crm.integration.service.MappedPayload;
import com.xetax.crm.integration.service.PayloadMappingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the last payload left over is kept on the integration so the panel can
 * show it. Before this, a key with no mapping was dropped in silence and the
 * only symptom was a record with an empty field.
 */
class IngestDiagnosticsTest {

    private IntegrationRepository integrationRepository;
    private PayloadMappingService payloadMappingService;
    private RecordService recordService;
    private IntegrationIngestServiceImpl service;

    private Integration integration;

    @BeforeEach
    void setUp() {
        integrationRepository = mock(IntegrationRepository.class);
        payloadMappingService = mock(PayloadMappingService.class);
        recordService = mock(RecordService.class);

        FormEntity form = FormEntity.builder().name("Leads").slug("leads").build();
        form.setId(5L);

        integration = Integration.builder()
                .name("Website form")
                .integrationKey("key-1")
                .apiKey("secret-1")
                .form(form)
                .type(IntegrationType.GENERIC_WEBHOOK)
                .status(IntegrationStatus.ACTIVE)
                .build();
        integration.setId(1L);

        when(integrationRepository.findByIntegrationKey("key-1"))
                .thenReturn(Optional.of(integration));
        when(integrationRepository.save(any(Integration.class)))
                .thenAnswer(call -> call.getArgument(0));

        service = new IntegrationIngestServiceImpl(
                integrationRepository, payloadMappingService, recordService);
    }

    private void mappingReturns(MappedPayload result) {
        when(payloadMappingService.apply(anyLong(), any())).thenReturn(result);
    }

    @Test
    void whatWasDroppedIsKeptOnTheIntegration() {
        mappingReturns(new MappedPayload(
                Map.of("phone", "9812345678"),
                List.of("utm_source", "city"),
                List.of("email")));

        service.ingest("key-1", "secret-1", Map.of("mobile", "9812345678"));

        assertEquals("utm_source,city", integration.getLastIgnoredKeys());
        assertEquals("email", integration.getLastUnmatchedFields());
        assertNotNull(integration.getLastPayloadAt());
    }

    @Test
    void aCleanPayloadClearsTheOldWarning() {
        integration.setLastIgnoredKeys("utm_source");
        integration.setLastUnmatchedFields("email");
        mappingReturns(new MappedPayload(Map.of("phone", "9812345678"), List.of(), List.of()));

        service.ingest("key-1", "secret-1", Map.of("mobile", "9812345678"));

        assertNull(integration.getLastIgnoredKeys(),
                "a stale warning would send someone hunting a problem they already fixed");
        assertNull(integration.getLastUnmatchedFields());
    }

    @Test
    void theCallerIsToldWhatWasIgnored() {
        mappingReturns(new MappedPayload(
                Map.of("phone", "9812345678"), List.of("utm_source"), List.of()));

        MappedPayload result =
                service.ingest("key-1", "secret-1", Map.of("mobile", "9812345678"));

        assertEquals(List.of("utm_source"), result.ignoredPaths());
    }

    @Test
    void theRecordIsStillCreatedFromTheMappedData() {
        mappingReturns(new MappedPayload(Map.of("phone", "9812345678"), List.of(), List.of()));

        service.ingest("key-1", "secret-1", Map.of("mobile", "9812345678"));

        ArgumentCaptor<RecordRequest> captor = ArgumentCaptor.forClass(RecordRequest.class);
        verify(recordService).create(anyString(), captor.capture());
        assertEquals(Map.of("phone", "9812345678"), captor.getValue().getData());
    }

    @Test
    void aDisabledIntegrationRefusesThePayload() {
        integration.setStatus(IntegrationStatus.DISABLED);

        assertThrows(IntegrationInactiveException.class,
                () -> service.ingest("key-1", "secret-1", Map.of("mobile", "9812345678")));

        verify(recordService, never()).create(anyString(), any());
    }
}
