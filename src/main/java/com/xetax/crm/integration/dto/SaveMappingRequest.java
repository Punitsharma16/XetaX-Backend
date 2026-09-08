package com.xetax.crm.integration.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class SaveMappingRequest {

    @NotEmpty
    private List<MappingItemRequest> mappings;

}
