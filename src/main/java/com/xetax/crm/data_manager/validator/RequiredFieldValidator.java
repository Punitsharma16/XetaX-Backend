package com.xetax.crm.data_manager.validator;

import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.entity.FormField;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class RequiredFieldValidator {

    public void validate(
            Map<String,Object> data,
            List<FormField> fields){

        for(FormField field : fields){

            if(Boolean.TRUE.equals(field.getRequired())){

                Object value = data.get(field.getFieldKey());

                if(value == null ||
                        value.toString().trim().isEmpty()){

                    throw new BadRequestException(
                            field.getLabel()+" is required"
                    );
                }
            }
        }
    }

}
