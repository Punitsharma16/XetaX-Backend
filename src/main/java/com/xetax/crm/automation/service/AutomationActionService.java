package com.xetax.crm.automation.service;

import com.xetax.crm.automation.dto.AutomationActionRequest;
import com.xetax.crm.automation.dto.AutomationActionResponse;

import java.util.List;

public interface AutomationActionService {

    List<AutomationActionResponse> saveActions(
            Long automationId,
            List<AutomationActionRequest> requests
    );

    List<AutomationActionResponse> getActions(
            Long automationId
    );

}
