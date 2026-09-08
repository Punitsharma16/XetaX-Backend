package com.xetax.crm.integration.dto;

import com.xetax.crm.integration.enums.IntegrationStatus;
import com.xetax.crm.integration.enums.IntegrationType;
import lombok.Data;

@Data
public class IntegrationResponse {

    private Long id;

    private String name;

    private String description;

    private IntegrationType type;

    /*
     * The target form, mirroring AutomationResponse. Without these the API never
     * told a client which form an integration belongs to: editing one had to
     * guess a formId (silently reassigning the integration), and field mapping
     * could not know which form's fields are valid — saveMappings rejects any
     * formFieldId outside the integration's own form.
     */
    private Long formId;

    private String formName;

    private IntegrationStatus status;

    private String integrationKey;

    private String apiKey;

    private String endpoint;

}
