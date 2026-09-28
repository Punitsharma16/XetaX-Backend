package com.xetax.crm.integration.service;

import com.xetax.crm.integration.entity.IntegrationFieldMapping;
import com.xetax.crm.integration.repository.IntegrationFieldMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayloadMappingServiceImpl
        implements PayloadMappingService {

    /**
     * A payload can carry hundreds of keys. Naming the first few is enough to
     * spot a rename; the whole list would only bloat the column and the log.
     */
    private static final int MAX_REPORTED = 25;

    private final IntegrationFieldMappingRepository mappingRepository;

    @Override
    public MappedPayload apply(Long integrationId, Map<String, Object> payload) {

        // Nested objects and arrays become dot paths ("customer.email",
        // "items.0.sku"). Top-level keys keep resolving exactly as before,
        // including a mapping that points at a whole object.
        Map<String, Object> flattened = PayloadFlattener.flatten(payload);

        List<IntegrationFieldMapping> mappings =
                mappingRepository.findByIntegrationId(integrationId);

        Map<String, Object> crmPayload = new HashMap<>();
        Set<String> claimed = new HashSet<>();
        List<String> unmatched = new ArrayList<>();

        for (IntegrationFieldMapping mapping : mappings) {
            String source = mapping.getSourceField();
            Object value = flattened.get(source);
            if (value != null) {
                crmPayload.put(mapping.getFormField().getFieldKey(), value);
                claimed.add(source);
            } else {
                unmatched.add(source);
            }
        }

        List<String> ignored = PayloadFlattener.leafPaths(flattened).stream()
                .filter(path -> !claimed.contains(path))
                .filter(path -> !insideClaimedContainer(path, claimed))
                .limit(MAX_REPORTED)
                .toList();

        return new MappedPayload(crmPayload, ignored, unmatched);
    }

    /**
     * A mapping on "customer" already took the whole object, so every
     * "customer.*" leaf under it was used — reporting them as ignored would be
     * noise.
     */
    private static boolean insideClaimedContainer(String path, Set<String> claimed) {
        for (String source : claimed) {
            if (path.startsWith(source + ".")) {
                return true;
            }
        }
        return false;
    }
}
