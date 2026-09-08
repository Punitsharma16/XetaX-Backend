package com.xetax.crm.integration.service;

import com.xetax.crm.integration.entity.Integration;
import com.xetax.crm.integration.entity.IntegrationFieldMapping;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the semantic-index text for a webhook integration. Shared by
 * IntegrationServiceImpl (create/update/delete) and
 * IntegrationMappingServiceImpl (mapping save = configuration update).
 *
 * <p>NEVER include integrationKey, apiKey or the public endpoint here —
 * those are credentials/secrets and must not reach Qdrant.
 */
final class IntegrationKnowledge {

    static final String MODULE = "integration";

    private IntegrationKnowledge() {
    }

    static String content(Integration integration, List<IntegrationFieldMapping> mappings) {
        StringBuilder sb = new StringBuilder();
        sb.append("CRM Webhook Integration \"").append(integration.getName())
                .append("\" of type ").append(integration.getType())
                .append(" on form \"").append(integration.getForm().getName()).append("\".");
        if (integration.getDescription() != null && !integration.getDescription().isBlank()) {
            sb.append(" Description: ").append(integration.getDescription()).append(".");
        }
        sb.append(" Status: ").append(integration.getStatus()).append(".");
        if (mappings != null && !mappings.isEmpty()) {
            sb.append(" Mapped source fields: ")
                    .append(mappings.stream()
                            .map(m -> m.getSourceField() + " -> " + m.getFormField().getLabel())
                            .collect(Collectors.joining(", ")))
                    .append(".");
        }
        return sb.toString();
    }
}
