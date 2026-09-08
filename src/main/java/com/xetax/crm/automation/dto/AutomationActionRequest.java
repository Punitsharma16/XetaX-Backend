package com.xetax.crm.automation.dto;

import com.xetax.crm.automation.enums.AutomationActionType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AutomationActionRequest {

    @NotNull
    private AutomationActionType actionType;

    private Long formFieldId;

    private String value;

    private String emailSubject;

    private String emailMessage;

    @NotNull
    private Integer executionOrder;

}
