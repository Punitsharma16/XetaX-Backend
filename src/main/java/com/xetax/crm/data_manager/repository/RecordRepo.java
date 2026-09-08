package com.xetax.crm.data_manager.repository;

import com.xetax.crm.data_manager.documents.RecordDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecordRepo extends MongoRepository<RecordDocument,String> {

    Page<RecordDocument> findByFormId(
            Long formId,
            Pageable pageable
    );
    List<RecordDocument> findByFormIdAndStageId(
            Long formId,
            Long stageId
    );


    List<RecordDocument> findByAssignedTo(String assignedTo);

    long countByFormId(Long formId);

    long countByFormIdAndAssignedTo(Long formId, String assignedTo);

    long countByFormIdIn(java.util.List<Long> formIds);

    long countByFormIdInAndCreatedAtGreaterThanEqual(java.util.List<Long> formIds, java.time.LocalDateTime after);

    long countByFormIdInAndAssignedToAndCreatedAtGreaterThanEqual(java.util.List<Long> formIds, String assignedTo, java.time.LocalDateTime after);

    long countByFormIdAndStageId(Long formId, Long stageId);

    long countByFormIdAndStageIdAndAssignedTo(Long formId, Long stageId, String assignedTo);

    long countByFormIdAndStageIdInAndUpdatedAtBefore(Long formId, java.util.List<Long> stageIds, java.time.LocalDateTime before);

    Page<RecordDocument> findByFormIdInOrderByCreatedAtDesc(java.util.List<Long> formIds, Pageable pageable);

    Page<RecordDocument> findByFormIdInAndAssignedToOrderByCreatedAtDesc(java.util.List<Long> formIds, String assignedTo, Pageable pageable);


    Page<RecordDocument> findByFormIdAndAssignedTo(Long formId, String assignedTo, Pageable pageable);
}
