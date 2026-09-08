package com.xetax.crm.automation.condition;

import com.xetax.crm.automation.entity.AutomationCondition;
import com.xetax.crm.automation.enums.ConditionOperator;

import java.util.Map;

public interface ConditionEvaluator {

    ConditionOperator getOperator();

    boolean evaluate(
            AutomationCondition condition,
            Map<String, Object> record
    );

}
