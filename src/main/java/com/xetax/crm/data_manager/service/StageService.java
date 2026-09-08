package com.xetax.crm.data_manager.service;

import com.xetax.crm.data_manager.dto.StageRequest;
import com.xetax.crm.data_manager.dto.StageResponse;

import java.util.List;

public interface StageService {

    StageResponse create(Long formId, StageRequest request);

    List<StageResponse> getAll(Long formId);

    StageResponse update(Long id, StageRequest request);

    void delete(Long id);
}
