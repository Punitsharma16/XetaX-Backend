package com.xetax.crm.automation.repository;

import com.xetax.crm.automation.entity.AutomationAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AutomationActionRepository extends JpaRepository<AutomationAction , Long> {


    List<AutomationAction> findByAutomationIdOrderByExecutionOrderAsc(Long automationId);

    void deleteByAutomationId(Long automationId);
}
