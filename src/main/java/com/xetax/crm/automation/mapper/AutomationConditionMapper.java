package com.xetax.crm.automation.mapper;

import com.xetax.crm.automation.entity.AutomationCondition;
import com.xetax.crm.automation.entity.AutomationConditionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AutomationConditionMapper {

    @Mapping(source = "formField.id", target = "formFieldId")
    @Mapping(source = "formField.label", target = "fieldName")
    @Mapping(source = "formField.fieldKey", target = "fieldKey")
    AutomationConditionResponse toResponse(
            AutomationCondition condition
    );

}
