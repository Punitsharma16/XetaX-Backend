package com.xetax.crm.automation.action;

import com.xetax.crm.automation.entity.AutomationAction;
import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormField;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * One executor covers both increase and decrease: the action value is a signed
 * amount ("10" adds, "-5" subtracts, blank means +1). A missing or non-numeric
 * current value counts as 0, so the first increment on an empty field simply
 * yields the amount.
 */
@Component
public class AdjustFieldActionExecutor implements ActionExecutor {

    @Override
    public AutomationActionType getActionType() {
        return AutomationActionType.ADJUST_FIELD;
    }

    @Override
    public void execute(AutomationAction action, RecordDocument record) {

        FormField field = action.getFormField();
        if (field == null) {
            throw new IllegalArgumentException(
                    "FormField is required for ADJUST_FIELD action."
            );
        }

        BigDecimal amount = parse(action.getValue(), BigDecimal.ONE);
        BigDecimal current = parse(
                String.valueOf(record.getData().get(field.getFieldKey())), BigDecimal.ZERO);

        BigDecimal result = current.add(amount);

        // Store whole numbers as longs so "5" doesn't come back as "5.0".
        Object stored = result.stripTrailingZeros().scale() <= 0
                ? (Object) result.longValueExact()
                : (Object) result.doubleValue();
        record.getData().put(field.getFieldKey(), stored);
    }

    private BigDecimal parse(String raw, BigDecimal fallback) {
        if (raw == null || raw.isBlank() || raw.equals("null")) {
            return fallback;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
