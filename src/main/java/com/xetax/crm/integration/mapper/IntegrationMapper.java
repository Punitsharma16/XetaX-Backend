package com.xetax.crm.integration.mapper;

import com.xetax.crm.integration.dto.IntegrationResponse;
import com.xetax.crm.integration.entity.Integration;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface IntegrationMapper {

    @Mapping(target = "endpoint", ignore = true)
    @Mapping(source = "form.id", target = "formId")
    @Mapping(source = "form.name", target = "formName")
    IntegrationResponse toResponse(Integration integration);

}
