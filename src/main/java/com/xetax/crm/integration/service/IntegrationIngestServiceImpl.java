package com.xetax.crm.integration.service;


import com.xetax.crm.common.exception.IntegrationInactiveException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.common.exception.UnauthorizedException;
import com.xetax.crm.data_manager.dto.RecordRequest;
import com.xetax.crm.data_manager.service.RecordService;
import com.xetax.crm.integration.entity.Integration;
import com.xetax.crm.integration.enums.IntegrationStatus;
import com.xetax.crm.integration.repository.IntegrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional
public class IntegrationIngestServiceImpl
        implements IntegrationIngestService {

    private final IntegrationRepository integrationRepository;

    private final PayloadMappingService payloadMappingService;

    private final RecordService recordService;

    @Override
    public void ingest(String integrationKey, String apiKey,
                       Map<String, Object> payload) {

        Integration integration = integrationRepository
                .findByIntegrationKey(integrationKey)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Integration not found"));

        if (!integration.getApiKey().equals(apiKey)) {
            throw new UnauthorizedException("Invalid API Key");
        }

        if (integration.getStatus() != IntegrationStatus.ACTIVE) {
            throw new IntegrationInactiveException("Integration is not active");
        }

        Map<String, Object> mappedPayload =
                payloadMappingService.map(
                        integration.getId(),
                        payload
                );

        RecordRequest request = new RecordRequest();
        request.setData(mappedPayload);

        recordService.create(
                integration.getForm().getSlug(),
                request
        );
    }
}
