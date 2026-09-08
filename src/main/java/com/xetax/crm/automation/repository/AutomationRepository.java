package com.xetax.crm.automation.repository;

import com.xetax.crm.automation.entity.Automation;
import com.xetax.crm.automation.enums.AutomationTrigger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AutomationRepository
        extends JpaRepository<Automation, Long> {

    java.util.List<Automation> findByFormId(Long formId);

    java.util.List<Automation> findByFormIdIn(java.util.List<Long> formIds);


    List<Automation> findByFormIdAndTriggerAndActiveTrue(
            Long formId,
            AutomationTrigger trigger
    );

}
