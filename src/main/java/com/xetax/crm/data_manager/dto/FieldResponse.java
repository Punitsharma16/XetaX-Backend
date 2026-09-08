package com.xetax.crm.data_manager.dto;

import com.xetax.crm.data_manager.enums.FieldType;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FieldResponse {

    private Long id;

    private Long formId;

    private String label;

    private String fieldKey;

    private FieldType fieldType;

    private Boolean required;

    private Boolean uniqueField;

    private String placeholder;

    private String defaultValue;

    private String validationJson;

    private String optionsJson;

    private Integer displayOrder;

    private Boolean searchable;

    private Boolean filterable;

    private Boolean sortable;

    private Boolean hidden;

}