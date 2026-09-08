package com.xetax.crm.automation.action;

import com.xetax.crm.automation.entity.AutomationAction;
import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.data_manager.documents.RecordDocument;

public interface ActionExecutor {

    AutomationActionType getActionType();

    void execute(AutomationAction action, RecordDocument record);

    /**
     * Whether execute() writes into the record. The engine only re-saves the
     * record when a mutating action ran — SEND_EMAIL overrides this to false.
     */
    default boolean mutatesRecord() {
        return true;
    }

}
