package com.xetax.crm.automation.condition;

import com.xetax.crm.automation.entity.AutomationCondition;
import com.xetax.crm.automation.enums.ConditionOperator;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EqualsConditionEvaluator
        implements ConditionEvaluator {

    @Override
    public ConditionOperator getOperator() {
        return ConditionOperator.EQUALS;
    }

    @Override
    public boolean evaluate(
            AutomationCondition condition,
            Map<String, Object> record) {

        Object value = record.get(
                        condition.getFormField().getFieldKey()
                );

        if (value == null) {
            return false;
        }

        return value.toString().equals(condition.getValue());
    }

}
