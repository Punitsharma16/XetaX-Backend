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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class IntegrationIngestServiceImpl
        implements IntegrationIngestService {

    /** Matches the column width on Integration. */
    private static final int DIAGNOSTIC_LENGTH = 2000;

    private final IntegrationRepository integrationRepository;

    private final PayloadMappingService payloadMappingService;

    private final RecordService recordService;

    @Override
    public MappedPayload ingest(String integrationKey, String apiKey,
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

        MappedPayload mapped =
                payloadMappingService.apply(
                        integration.getId(),
                        payload
                );

        RecordRequest request = new RecordRequest();
        request.setData(mapped.data());

        recordService.create(
                integration.getForm().getSlug(),
                request
        );

        recordDiagnostics(integration, mapped);

        return mapped;
    }

    /**
     * Keeps the last payload's leftovers on the integration so the panel can
     * show them, and warns in the log when there were any. A field the sender
     * renamed shows up here rather than as a quietly empty record.
     */
    private void recordDiagnostics(Integration integration, MappedPayload mapped) {

        integration.setLastPayloadAt(LocalDateTime.now());
        integration.setLastIgnoredKeys(join(mapped.ignoredPaths()));
        integration.setLastUnmatchedFields(join(mapped.unmatchedFields()));
        integrationRepository.save(integration);

        if (!mapped.ignoredPaths().isEmpty() || !mapped.unmatchedFields().isEmpty()) {
            log.warn("Integration {} (\"{}\") took a payload with unmapped keys {}"
                            + " and no value for mapped fields {}",
                    integration.getId(), integration.getName(),
                    mapped.ignoredPaths(), mapped.unmatchedFields());
        }
    }

    private static String join(List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        String joined = String.join(",", values);
        return joined.length() > DIAGNOSTIC_LENGTH
                ? joined.substring(0, DIAGNOSTIC_LENGTH)
                : joined;
    }
}
