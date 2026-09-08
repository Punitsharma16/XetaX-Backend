package com.xetax.crm.data_manager.mappers;

import com.xetax.crm.data_manager.dto.FieldRequest;
import com.xetax.crm.data_manager.dto.FormRequest;
import com.xetax.crm.data_manager.dto.FormResponse;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

@Mapper(componentModel = "spring")
public interface FormMapper {

    /*
     * `id` is inherited from BaseEntity, and Lombok's plain @Builder does not
     * expose inherited fields — FormEntityBuilder therefore has no id to
     * ignore. A new entity is created without one either way, so the mapping
     * is simply left off.
     */
    @Mapping(target = "status", constant = "ACTIVE")
    FormEntity toEntity(FormRequest request);

    FormResponse toResponse(FormEntity form);

    List<FormResponse> toResponse(List<FormEntity> forms);

    void updateEntity(FormRequest request,
                      @MappingTarget FormEntity entity);


}
