package com.xetax.crm.integration.service;

import com.xetax.crm.integration.entity.IntegrationFieldMapping;
import com.xetax.crm.integration.repository.IntegrationFieldMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayloadMappingServiceImpl
        implements PayloadMappingService {

    private final IntegrationFieldMappingRepository mappingRepository;

    @Override
    public Map<String, Object> map(Long integrationId, Map<String, Object> payload) {

        // Normalize incoming payload keys
        Map<String, Object> normalizedPayload = payload.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().trim().toLowerCase(),
                        Map.Entry::getValue
                ));

        List<IntegrationFieldMapping> mappings = mappingRepository.findByIntegrationId(integrationId);

        Map<String, Object> crmPayload = new HashMap<>();

        for (IntegrationFieldMapping mapping : mappings) {
            Object value = normalizedPayload.get(mapping.getSourceField());
            if (value != null) {
                crmPayload.put(
                        mapping.getFormField().getFieldKey(),
                        value
                );
            }
        }

        return crmPayload;
    }
}
