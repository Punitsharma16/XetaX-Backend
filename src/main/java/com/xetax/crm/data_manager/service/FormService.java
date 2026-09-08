package com.xetax.crm.data_manager.service;

import com.xetax.crm.data_manager.dto.FormRequest;
import com.xetax.crm.data_manager.dto.FormResponse;
import jakarta.validation.Valid;

import java.util.List;

public interface FormService {


    public FormResponse create(@Valid FormRequest request);

    public List<FormResponse> getAll();

    public FormResponse getById(Long id);

    public FormResponse update(Long id, @Valid FormRequest request);

    public void delete(Long id);
}
