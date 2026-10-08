package com.xetax.crm.automation.condition;

import com.xetax.crm.automation.entity.AutomationCondition;
import com.xetax.crm.automation.enums.ConditionOperator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/** True while the field is > the threshold. See {@link NumericComparison}. */
@Component
public class GreaterThanConditionEvaluator implements ConditionEvaluator {

    @Override
    public ConditionOperator getOperator() {
        return ConditionOperator.GREATER_THAN;
    }

    @Override
    public boolean evaluate(AutomationCondition condition, Map<String, Object> record) {
        BigDecimal left = NumericComparison.left(condition, record);
        BigDecimal right = NumericComparison.right(condition, record);
        if (left == null || right == null) {
            return false;
        }
        return left.compareTo(right) > 0;
    }
}
