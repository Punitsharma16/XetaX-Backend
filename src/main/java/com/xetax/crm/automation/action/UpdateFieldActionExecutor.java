package com.xetax.crm.automation.action;

import com.xetax.crm.automation.entity.AutomationAction;
import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormField;
import org.springframework.stereotype.Component;

@Component
public class UpdateFieldActionExecutor
        implements ActionExecutor {

    @Override
    public AutomationActionType getActionType() {
        return AutomationActionType.UPDATE_FIELD;
    }

    @Override
    public void execute(AutomationAction action, RecordDocument record) {

        FormField field = action.getFormField();
        if (field == null) {
            throw new IllegalArgumentException(
                    "FormField is required for UPDATE_FIELD action."
            );
        }
        record.getData().put(field.getFieldKey(), action.getValue());
    }
}
