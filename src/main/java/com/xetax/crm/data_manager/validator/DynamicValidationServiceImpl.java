package com.xetax.crm.data_manager.validator;

import com.xetax.crm.data_manager.dto.RecordRequest;
import com.xetax.crm.data_manager.entity.FormField;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DynamicValidationServiceImpl implements DynamicValidationService {

    private final RequiredFieldValidator requiredFieldValidator;

    private final UnknownFieldValidator unknownFieldValidator;

    private final DefaultValueValidator defaultValueValidator;

    private final FieldTypeValidator fieldTypeValidator;

//    private final UniqueFieldValidator uniqueFieldValidator;

    @Override
    public Map<String,Object> validate(
            RecordRequest request,
            Long formId,
            List<FormField> fields){

        Map<String,Object> data =
                new HashMap<>(request.getData());

        unknownFieldValidator.validate(data,fields);

        requiredFieldValidator.validate(data,fields);

        defaultValueValidator.validate(data,fields);

        fieldTypeValidator.validate(data,fields);

//        uniqueFieldValidator.validate(formId,data,fields);

        return data;
    }

    @Override
    public Map<String, Object> validate(RecordRequest request, List<FormField> fields) {
        Map<String,Object> data =
                new HashMap<>(request.getData());

        unknownFieldValidator.validate(data,fields);

        requiredFieldValidator.validate(data,fields);

        defaultValueValidator.validate(data,fields);

        fieldTypeValidator.validate(data,fields);

//        uniqueFieldValidator.validate(formId,data,fields);

        return data;
    }
}
