package com.xetax.crm.data_manager.repository;

import com.xetax.crm.data_manager.entity.FormStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StageRepo extends JpaRepository<FormStage , Long> {


    List<FormStage> findByFormIdOrderBySequence(Long formId);

    boolean existsByFormIdAndCode(Long formId, String code);

    /* Same check while editing one stage: the row being edited must not count
       as its own duplicate. MySQL compares case-insensitively, so renaming
       "won" to "WON" collided with itself without this. */
    boolean existsByFormIdAndCodeAndIdNot(Long formId, String code, Long id);

    boolean existsByFormIdAndSequence(Long formId, Integer sequence);

    boolean existsByFormIdAndIsDefaultTrue(Long formId);

    Optional<FormStage> findByFormIdAndIsDefaultTrue(Long formId);

}
