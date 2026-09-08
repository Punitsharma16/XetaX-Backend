package com.xetax.crm.automation.mapper;

import com.xetax.crm.automation.dto.AutomationActionResponse;
import com.xetax.crm.automation.entity.AutomationAction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AutomationActionMapper {

//    @Mapping(source = "formField.id", target = "formFieldId")
    @Mapping(source = "formField.label", target = "fieldName")
    @Mapping(source = "formField.fieldKey", target = "fieldKey")
    AutomationActionResponse toResponse(AutomationAction action);

}
