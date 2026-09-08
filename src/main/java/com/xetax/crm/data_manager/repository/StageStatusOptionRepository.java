package com.xetax.crm.data_manager.repository;

import com.xetax.crm.data_manager.entity.StageStatusOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StageStatusOptionRepository extends JpaRepository<StageStatusOption, Long> {

    List<StageStatusOption> findByStageIdOrderBySequenceAsc(Long stageId);

    List<StageStatusOption> findByFormIdOrderBySequenceAsc(Long formId);

    Optional<StageStatusOption> findByIdAndFormId(Long id, Long formId);

    void deleteByStageId(Long stageId);
}
