package com.xetax.crm.integration.service;

import com.xetax.crm.integration.dto.IntegrationRequest;
import com.xetax.crm.integration.dto.IntegrationResponse;
import com.xetax.crm.integration.enums.IntegrationStatus;

import java.util.List;

public interface IntegrationService {

    IntegrationResponse create(IntegrationRequest request);

    IntegrationResponse update(Long id, IntegrationRequest request);

    IntegrationResponse getById(Long id);

    List<IntegrationResponse> getAll();

    /**
     * Turns an integration on or off. Accepts ACTIVE or DISABLED only; a
     * disabled integration answers ingest calls the same way a pending one
     * does, so this is the off switch that used to be missing.
     */
    IntegrationResponse setStatus(Long id, IntegrationStatus status);

    void delete(Long id);
}
