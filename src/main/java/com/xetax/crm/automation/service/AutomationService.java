package com.xetax.crm.automation.service;

import com.xetax.crm.automation.dto.AutomationRequest;
import com.xetax.crm.automation.dto.AutomationResponse;

import java.util.List;

public interface AutomationService {

    AutomationResponse create(AutomationRequest request);

    AutomationResponse update(Long id, AutomationRequest request);

    AutomationResponse getById(Long id);

    List<AutomationResponse> getAll();

    void delete(Long id);

}
