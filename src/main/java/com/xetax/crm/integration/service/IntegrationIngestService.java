package com.xetax.crm.integration.service;

import java.util.Map;

public interface IntegrationIngestService {

    /**
     * Stores one inbound payload as a record on the integration's form.
     *
     * @return what the mapping did with it, so the caller can tell the sender
     *         which keys were ignored instead of answering an opaque 200.
     */
    MappedPayload ingest(String integrationKey, String apiKey, Map<String, Object> payload);

}
