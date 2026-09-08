package com.xetax.crm.automation.repository;

import com.xetax.crm.automation.entity.AutomationCondition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AutomationConditionRepository
        extends JpaRepository<AutomationCondition, Long> {

    List<AutomationCondition> findByAutomationId(Long automationId);

    void deleteByAutomationId(Long automationId);
}
