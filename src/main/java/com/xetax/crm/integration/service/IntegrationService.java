package com.xetax.crm.integration.service;

import com.xetax.crm.integration.dto.IntegrationRequest;
import com.xetax.crm.integration.dto.IntegrationResponse;

import java.util.List;

public interface IntegrationService {

    IntegrationResponse create(IntegrationRequest request);

    IntegrationResponse update(Long id, IntegrationRequest request);

    IntegrationResponse getById(Long id);

    List<IntegrationResponse> getAll();

    void delete(Long id);
}
