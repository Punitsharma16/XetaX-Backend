package com.xetax.crm.data_manager.mappers;

import com.xetax.crm.data_manager.dto.FieldRequest;
import com.xetax.crm.data_manager.dto.FieldResponse;
import com.xetax.crm.data_manager.entity.FormField;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

@Mapper(componentModel = "spring")
public interface FormFieldMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "formId", ignore = true)
    FormField toEntity(FieldRequest request);

    FieldResponse toResponse(FormField entity);

    List<FieldResponse> toResponse(List<FormField> entity);

    void updateEntity(FieldRequest request,
                      @MappingTarget FormField entity);

}
