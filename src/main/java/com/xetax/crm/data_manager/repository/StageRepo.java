package com.xetax.crm.data_manager.repository;

import com.xetax.crm.data_manager.entity.FormStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StageRepo extends JpaRepository<FormStage , Long> {


    List<FormStage> findByFormIdOrderBySequence(Long formId);

    boolean existsByFormIdAndCode(Long formId, String code);

    boolean existsByFormIdAndSequence(Long formId, Integer sequence);

    boolean existsByFormIdAndIsDefaultTrue(Long formId);

    Optional<FormStage> findByFormIdAndIsDefaultTrue(Long formId);

}
