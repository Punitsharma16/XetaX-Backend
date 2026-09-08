package com.xetax.crm.automation.entity;

import com.xetax.crm.automation.enums.ConditionOperator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AutomationConditionRequest {

    @NotNull
    private Long formFieldId;

    @NotNull
    private ConditionOperator operator;

    @NotBlank
    private String value;
}
