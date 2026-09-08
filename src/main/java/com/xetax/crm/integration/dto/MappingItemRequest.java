package com.xetax.crm.integration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MappingItemRequest {

    @NotBlank
    private String sourceField;

    @NotNull
    private Long formFieldId;

}
