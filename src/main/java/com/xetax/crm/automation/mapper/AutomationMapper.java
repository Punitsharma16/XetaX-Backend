package com.xetax.crm.automation.mapper;

import com.xetax.crm.automation.dto.AutomationResponse;
import com.xetax.crm.automation.entity.Automation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AutomationMapper {

    @Mapping(source = "form.id", target = "formId")
    @Mapping(source = "form.name", target = "formName")
    @Mapping(source = "actionField.id", target = "actionFieldId")
    @Mapping(source = "actionField.label", target = "actionFieldName")
    @Mapping(source = "actionField.fieldKey", target = "actionFieldKey")
    AutomationResponse toResponse(Automation automation);

}
