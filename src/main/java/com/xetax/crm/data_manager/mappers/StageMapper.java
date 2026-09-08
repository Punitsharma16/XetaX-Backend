package com.xetax.crm.data_manager.mappers;

import com.xetax.crm.data_manager.dto.StageRequest;
import com.xetax.crm.data_manager.dto.StageResponse;
import com.xetax.crm.data_manager.entity.FormStage;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

@Mapper(componentModel = "spring")
public interface StageMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "formId", ignore = true)
    FormStage toEntity(StageRequest request);

    StageResponse toResponse(FormStage entity);

    List<StageResponse> toResponse(List<FormStage> entity);

    void updateEntity(StageRequest request,
                      @MappingTarget FormStage entity);
}
