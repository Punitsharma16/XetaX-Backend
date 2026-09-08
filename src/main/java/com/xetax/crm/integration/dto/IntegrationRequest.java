package com.xetax.crm.integration.dto;

import com.xetax.crm.integration.enums.IntegrationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class IntegrationRequest {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    private Long formId;

    @NotNull
    private IntegrationType type;
}

