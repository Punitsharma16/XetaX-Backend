package com.xetax.crm.integration.service;

import com.xetax.crm.integration.dto.MappingItemResponse;
import com.xetax.crm.integration.dto.SaveMappingRequest;

import java.util.List;

public interface IntegrationMappingService {

    void saveMappings(
            Long integrationId,
            SaveMappingRequest request
    );

    List<MappingItemResponse> getMappings(Long integrationId);

}
