package com.xetax.crm.data_manager.dto;

import com.xetax.crm.data_manager.enums.FieldType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FieldRequest {

    @NotBlank(message = "Label is required")
    private String label;

    @NotBlank(message = "Field key is required")
    private String fieldKey;

    @NotNull(message = "Field type is required")
    private FieldType fieldType;

    private Boolean required = false;

    private Boolean uniqueField = false;

    private String placeholder;

    private String defaultValue;

    private String validationJson;

    private String optionsJson;

    private Integer displayOrder;

    private Boolean searchable = false;

    private Boolean filterable = false;

    private Boolean sortable = false;

    private Boolean hidden = false;

}
