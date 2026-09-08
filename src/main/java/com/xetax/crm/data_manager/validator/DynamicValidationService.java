package com.xetax.crm.data_manager.validator;

import com.xetax.crm.data_manager.dto.RecordRequest;
import com.xetax.crm.data_manager.entity.FormField;

import java.util.List;
import java.util.Map;

public interface DynamicValidationService {

    Map<String,Object> validate(
            RecordRequest request,
            Long formId,
            List<FormField> fields);

    Map<String, Object> validate(
            RecordRequest request,
            List<FormField> fields
    );
}
