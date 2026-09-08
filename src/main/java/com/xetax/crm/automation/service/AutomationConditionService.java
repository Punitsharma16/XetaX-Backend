package com.xetax.crm.automation.service;

import com.xetax.crm.automation.entity.AutomationConditionRequest;
import com.xetax.crm.automation.entity.AutomationConditionResponse;

import java.util.List;

public interface AutomationConditionService {

    List<AutomationConditionResponse> saveConditions(Long automationId, List<AutomationConditionRequest> request);

    List<AutomationConditionResponse> getConditions(Long automationId);

}
