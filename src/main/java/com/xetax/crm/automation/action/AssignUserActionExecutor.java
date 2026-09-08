package com.xetax.crm.automation.action;

import com.xetax.crm.automation.entity.AutomationAction;
import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.data_manager.documents.RecordDocument;
import org.springframework.stereotype.Component;

@Component
public class AssignUserActionExecutor implements ActionExecutor {

    @Override
    public AutomationActionType getActionType() {
        return AutomationActionType.ASSIGN_USER;
    }

    @Override
    public void execute(AutomationAction action, RecordDocument record) {

        if (action.getValue() == null || action.getValue().isBlank()) {
            throw new IllegalArgumentException(
                    "User Id is required for ASSIGN_USER action."
            );
        }

        String value = action.getValue().trim();
        try {
            // New model: team-member UUID -> drives approval chains + view.own.
            java.util.UUID memberId = java.util.UUID.fromString(value);
            record.setAssignedTo(memberId.toString());
        } catch (IllegalArgumentException notUuid) {
            // Legacy numeric assignment kept working exactly as before.
            record.setUserId(Long.parseLong(value));
        }
    }
}
