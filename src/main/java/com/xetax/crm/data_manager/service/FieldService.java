package com.xetax.crm.data_manager.service;

import com.xetax.crm.data_manager.dto.FieldRequest;
import com.xetax.crm.data_manager.dto.FieldResponse;

import java.util.List;

public interface FieldService {

    FieldResponse create(Long formId, FieldRequest request);

    List<FieldResponse> getAll(Long formId);

    FieldResponse update(Long id, FieldRequest request);

    void delete(Long id);
}
