package com.xetax.crm.data_manager.validator;

import com.xetax.crm.data_manager.entity.FormField;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DefaultValueValidator {

    public void validate(
            Map<String,Object> data,
            List<FormField> fields){

        for(FormField field : fields){

            if(data.containsKey(field.getFieldKey()))
                continue;

            if(field.getDefaultValue()!=null){

                data.put(
                        field.getFieldKey(),
                        field.getDefaultValue()
                );

            }
        }
    }

}
