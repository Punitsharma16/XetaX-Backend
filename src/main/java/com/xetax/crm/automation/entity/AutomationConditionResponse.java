package com.xetax.crm.automation.entity;

import com.xetax.crm.automation.enums.ConditionOperator;
import lombok.Data;

@Data
public class AutomationConditionResponse {

    private Long id;

    private Long formFieldId;

    private String fieldName;

    private String fieldKey;

    private ConditionOperator operator;

    private String value;

}
