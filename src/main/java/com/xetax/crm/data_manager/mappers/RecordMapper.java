package com.xetax.crm.data_manager.mappers;

import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.dto.RecordResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface RecordMapper {

    RecordResponse toResponse(RecordDocument entity);

    List<RecordResponse> toResponse(List<RecordDocument> entity);

}
