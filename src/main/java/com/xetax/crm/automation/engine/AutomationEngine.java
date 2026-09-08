package com.xetax.crm.automation.engine;

import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormEntity;

import java.util.Map;

public interface AutomationEngine {

    void execute(AutomationTrigger trigger,
                 FormEntity form,
                 RecordDocument recordDocument);

}
