package com.xetax.crm.automation.dto;

import com.xetax.crm.automation.enums.AutomationActionType;
import lombok.Data;

/*
 * Mirrors AutomationConditionResponse: AutomationActionMapper maps
 * formField.id / formField.label / formField.fieldKey onto this type, so those
 * targets must exist here for the mapping to resolve.
 */
@Data
public class AutomationActionResponse {

    private Long id;

    private AutomationActionType actionType;

    private Long formFieldId;

    private String fieldName;

    private String fieldKey;

    private String value;

    private String emailSubject;

    private String emailMessage;

    private Integer executionOrder;

}
