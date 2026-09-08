package com.xetax.crm.automation.dto;

import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.automation.enums.AutomationTrigger;
import lombok.Data;

@Data
public class AutomationResponse {

    private Long id;

    private String name;

    private String description;

    private Long formId;

    private String formName;

    private AutomationTrigger trigger;

    private Boolean active;

    private Long triggerStageId;

    private Long triggerStatusId;

    private AutomationActionType actionType;

    private Long actionFieldId;

    private String actionFieldName;

    private String actionFieldKey;

    private String actionValue;

    private String emailSubject;

    private String emailMessage;

    private Long documentId;

    private String channel;

}
