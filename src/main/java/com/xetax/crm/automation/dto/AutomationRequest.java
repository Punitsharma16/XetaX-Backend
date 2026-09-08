package com.xetax.crm.automation.dto;

import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.automation.enums.AutomationTrigger;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AutomationRequest {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    private Long formId;

    @NotNull
    private AutomationTrigger trigger;

    /** STAGE_CHANGED only: fire when the record lands on this stage (null = any). */
    private Long triggerStageId;

    /** STATUS_CHANGED only: fire when the record lands on this status (null = any). */
    private Long triggerStatusId;

    /** Single-table rule: the action this automation performs. */
    private AutomationActionType actionType;

    /** Field the action targets; for SEND_EMAIL, the field holding the recipient. */
    private Long actionFieldId;

    /** UPDATE_FIELD: new value · CHANGE_STAGE: stage id · ASSIGN_USER: user id
        · ADJUST_FIELD: signed amount ("10" adds, "-5" subtracts). */
    private String actionValue;

    /** null => active (backward compatible); templates create rules INACTIVE. */
    private Boolean active;

    private String emailSubject;

    private String emailMessage;

    /** SEND_DOCUMENT: the stored document to personalize and send. */
    private Long documentId;

    /** SEND_DOCUMENT: WHATSAPP | EMAIL. */
    private String channel;

}
