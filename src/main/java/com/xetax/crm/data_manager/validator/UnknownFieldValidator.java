package com.xetax.crm.data_manager.validator;

import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.entity.FormField;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class UnknownFieldValidator {

    public void validate(
            Map<String,Object> data,
            List<FormField> fields){

        Set<String> allowedFields =
                fields.stream()
                        .map(FormField::getFieldKey)
                        .collect(Collectors.toSet());

        for(String key : data.keySet()){

            if(!allowedFields.contains(key)){
                throw new BadRequestException(
                        "Unknown field : "+key
                );
            }
        }

    }

}
