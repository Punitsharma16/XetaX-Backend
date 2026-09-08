package com.xetax.crm.automation.action;

import com.xetax.crm.automation.entity.AutomationAction;
import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.data_manager.documents.RecordDocument;
import org.springframework.stereotype.Component;

@Component
public class ChangeStageActionExecutor
        implements ActionExecutor {

    @Override
    public AutomationActionType getActionType() {
        return AutomationActionType.CHANGE_STAGE;
    }

    @Override
    public void execute(AutomationAction action, RecordDocument record) {

        if (action.getValue() == null || action.getValue().isBlank()) {
            throw new IllegalArgumentException(
                    "Stage Id is required for CHANGE_STAGE action."
            );
        }

        Long stageId = Long.parseLong(action.getValue());
        record.setStageId(stageId);
    }
}
