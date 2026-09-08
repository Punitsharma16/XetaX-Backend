package com.xetax.crm.data_manager.service;

import com.xetax.crm.data_manager.dto.RecordRequest;
import com.xetax.crm.data_manager.dto.RecordResponse;
import com.xetax.crm.data_manager.dto.SeacrhDto.RecordSearchRequest;
import org.springframework.data.domain.Page;

import java.util.List;

public interface RecordService {

    RecordResponse create(String slug, RecordRequest request);

    Page<RecordResponse> getAll(String slug, int page, int size, String sort, String direction
    );

    RecordResponse getById(String id);

    RecordResponse update(String id, RecordRequest request);

    /** Moves a record to another stage of its own form. */
    RecordResponse changeStage(String id, Long stageId);

    Page<RecordResponse> search(String slug,
                                RecordSearchRequest request);

    void delete(String id);

    /** Assigns the given records to a team member. Returns how many moved. */
    int transfer(List<String> recordIds, String toUserId);

    /** Moves EVERY record currently assigned to one member onto another. */
    int transferAll(String fromUserId, String toUserId);

}
