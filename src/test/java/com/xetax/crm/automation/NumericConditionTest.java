package com.xetax.crm.automation;

import com.xetax.crm.automation.condition.GreaterThanConditionEvaluator;
import com.xetax.crm.automation.condition.LessThanConditionEvaluator;
import com.xetax.crm.automation.entity.AutomationCondition;
import com.xetax.crm.automation.enums.ConditionOperator;
import com.xetax.crm.data_manager.entity.FormField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Alert me when stock drops below its reorder level" could not be written at
 * all before: the only operators were EQUALS and NOT_EQUALS, and the value
 * compared against was always a fixed string.
 *
 * <p>A threshold that names another field is what makes the rule per-record —
 * every item has its own reorder level. Anything that is not a number on
 * either side must read as false: a rule nobody can judge must not fire, and
 * must never take a record save down with it.
 */
class NumericConditionTest {

    private LessThanConditionEvaluator less;
    private GreaterThanConditionEvaluator greater;

    @BeforeEach
    void setUp() {
        less = new LessThanConditionEvaluator();
        greater = new GreaterThanConditionEvaluator();
    }

    private static AutomationCondition on(String fieldKey, String value) {
        AutomationCondition c = new AutomationCondition();
        c.setFormField(FormField.builder().fieldKey(fieldKey).build());
        c.setValue(value);
        return c;
    }

    private static Map<String, Object> record(Object... pairs) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }

    // ------------------------------------------------------- the operators

    @Test
    void theOperatorsAreWiredToTheirEnum() {
        assertEquals(ConditionOperator.LESS_THAN, less.getOperator());
        assertEquals(ConditionOperator.GREATER_THAN, greater.getOperator());
    }

    @Test
    void comparesAgainstAFixedNumber() {
        assertTrue(less.evaluate(on("current_stock", "10"), record("current_stock", 4)));
        assertFalse(less.evaluate(on("current_stock", "10"), record("current_stock", 40)));
        assertTrue(greater.evaluate(on("current_stock", "10"), record("current_stock", 40)));
    }

    @Test
    void theBoundaryIsNotInside() {
        assertFalse(less.evaluate(on("current_stock", "10"), record("current_stock", 10)));
        assertFalse(greater.evaluate(on("current_stock", "10"), record("current_stock", 10)));
    }

    @Test
    void readsNumbersHoweverTheRecordStoresThem() {
        for (Object stored : new Object[] {4, 4L, 4.0, "4", " 4 ", BigDecimal.valueOf(4)}) {
            assertTrue(less.evaluate(on("current_stock", "10"), record("current_stock", stored)),
                    "should read " + stored.getClass().getSimpleName() + " as a number");
        }
    }

    @Test
    void handlesDecimals() {
        assertTrue(less.evaluate(on("unit_price", "99.95"), record("unit_price", "99.94")));
        assertFalse(less.evaluate(on("unit_price", "99.95"), record("unit_price", "99.96")));
    }

    @Test
    void handlesNegatives() {
        assertTrue(less.evaluate(on("current_stock", "0"), record("current_stock", -2)));
    }

    // ------------------------------------- a threshold that is another field

    @Test
    void comparesOneFieldAgainstAnother() {
        AutomationCondition lowStock = on("current_stock", "{reorder_level}");

        assertTrue(less.evaluate(lowStock, record("current_stock", 3, "reorder_level", 10)));
        assertFalse(less.evaluate(lowStock, record("current_stock", 30, "reorder_level", 10)));
    }

    @Test
    void toleratesSpacesInsideThePlaceholder() {
        assertTrue(less.evaluate(on("current_stock", "{ reorder_level }"),
                record("current_stock", 3, "reorder_level", 10)));
    }

    // ------------------------------------------------ nothing to judge, so no

    @Test
    void saysNoWhenTheFieldIsMissing() {
        assertFalse(less.evaluate(on("current_stock", "10"), record()));
    }

    @Test
    void saysNoWhenTheOtherFieldIsMissing() {
        assertFalse(less.evaluate(on("current_stock", "{reorder_level}"), record("current_stock", 3)));
    }

    @Test
    void saysNoWhenEitherSideIsNotANumber() {
        assertFalse(less.evaluate(on("current_stock", "10"), record("current_stock", "many")));
        assertFalse(less.evaluate(on("current_stock", "soon"), record("current_stock", 3)));
    }

    @Test
    void saysNoForBlanksAndNulls() {
        assertFalse(less.evaluate(on("current_stock", "10"), record("current_stock", "")));
        assertFalse(less.evaluate(on("current_stock", "10"), record("current_stock", null)));
        assertFalse(less.evaluate(on("current_stock", null), record("current_stock", 3)));
    }

    @Test
    void anEmptyPlaceholderIsTreatedAsText() {
        // "{}" names no field; it must not blow up or read as zero.
        assertFalse(less.evaluate(on("current_stock", "{}"), record("current_stock", 3)));
    }
}
