package com.xetax.crm.integration.service;

import java.util.Map;

public interface PayloadMappingService {

    /**
     * Applies the integration's mappings and reports what was left over on
     * both sides — keys the payload sent that nothing wanted, and mapped
     * fields the payload never sent.
     */
    MappedPayload apply(Long integrationId, Map<String, Object> payload);

    /** Mapped data only, for callers that do not care about the diagnostics. */
    default Map<String, Object> map(Long integrationId, Map<String, Object> payload) {
        return apply(integrationId, payload).data();
    }

}
