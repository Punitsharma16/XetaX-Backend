package com.xetax.crm.integration.service;

import java.util.Map;

public interface IntegrationIngestService {

    void ingest(String integrationKey, String apiKey, Map<String, Object> payload);

}
