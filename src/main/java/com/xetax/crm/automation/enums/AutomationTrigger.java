package com.xetax.crm.automation.enums;

public enum AutomationTrigger {

    RECORD_CREATED,

    RECORD_UPDATED,

    /**
     * Fired by RecordService#changeStage when a record actually moves to a
     * different stage. Re-selecting the current stage is a no-op and does not
     * fire it.
     */
    STAGE_CHANGED,

    /**
     * Fired when a record's status (the sub-state inside a stage) changes.
     * triggerStageId, when set, narrows it to statuses of that stage.
     */
    STATUS_CHANGED

}
