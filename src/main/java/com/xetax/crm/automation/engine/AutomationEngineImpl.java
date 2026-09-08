package com.xetax.crm.automation.engine;

import com.xetax.crm.automation.action.ActionExecutor;
import com.xetax.crm.automation.condition.ConditionEvaluator;
import com.xetax.crm.automation.entity.Automation;
import com.xetax.crm.automation.entity.AutomationAction;
import com.xetax.crm.automation.entity.AutomationCondition;
import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.automation.enums.ConditionOperator;
import com.xetax.crm.automation.repository.AutomationActionRepository;
import com.xetax.crm.automation.repository.AutomationConditionRepository;
import com.xetax.crm.automation.repository.AutomationRepository;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.repository.RecordRepo;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;


@Service
@AllArgsConstructor
@Transactional
@Slf4j
public class AutomationEngineImpl implements AutomationEngine{

    private final AutomationRepository automationRepository;

    private final AutomationConditionRepository conditionRepository;

    private final AutomationActionRepository actionRepository;

    private final RecordRepo recordRepository;

    private final AutomationRuleCache automationRuleCache;

    private final List<ConditionEvaluator> conditionEvaluators;

    private final List<ActionExecutor> actionExecutors;

    private final Map<ConditionOperator, ConditionEvaluator> evaluatorMap =
            new EnumMap<>(ConditionOperator.class);

    private final Map<AutomationActionType, ActionExecutor> executorMap =
            new EnumMap<>(AutomationActionType.class);



    @PostConstruct
    public void init() {

        for (ConditionEvaluator evaluator : conditionEvaluators) {
            evaluatorMap.put(evaluator.getOperator(),evaluator);
        }

        for (ActionExecutor executor : actionExecutors) {
            executorMap.put(executor.getActionType(), executor);
        }
    }

    /*
     * ASYNC: automations (emails, field adjustments, stage moves) run on the
     * automationExecutor instead of the HTTP request thread — the record is
     * already saved before this is called, so the API response no longer
     * waits for rule execution. @Transactional (class level) still opens a
     * session in the worker thread for the lazy actionField/form access.
     */
    @org.springframework.scheduling.annotation.Async("automationExecutor")
    @Override
    public void execute(AutomationTrigger trigger, FormEntity form, RecordDocument record) {

        /* Most forms have no rules — the Redis presence-cache lets the
           common case return without touching the automations table. */
        if (!automationRuleCache.mayHaveRules(form.getId(), trigger)) {
            return;
        }

        List<Automation> automations =
                automationRepository.findByFormIdAndTriggerAndActiveTrue(form.getId(), trigger);

        if (automations.isEmpty()) {
            return;
        }

        boolean modified = false;

        for (Automation automation : automations) {

            /*
             * Stage-scoped STAGE_CHANGED rules: a triggerStageId narrows the
             * automation to one target stage; null keeps the old fire-on-any-
             * move behaviour.
             */
            if (trigger == AutomationTrigger.STAGE_CHANGED
                    && automation.getTriggerStageId() != null
                    && !automation.getTriggerStageId().equals(record.getStageId())) {
                continue;
            }

            // Record statuses were removed — legacy STATUS_CHANGED rules stay in
            // the table but can never fire (nothing publishes that trigger now).
            if (trigger == AutomationTrigger.STATUS_CHANGED) {
                continue;
            }

            /*
             * One broken automation must not take down the rest — or the API
             * call itself, since the record is already saved by the time the
             * engine runs. Failures are logged and the loop moves on.
             */
            try {
                modified |= runAutomation(automation, record);
            } catch (Exception ex) {
                log.error("Automation {} ({}) failed on record {}: {}",
                        automation.getId(), automation.getName(), record.getId(),
                        ex.getMessage(), ex);
            }
        }

        if (modified) {
            recordRepository.save(record);
        }
    }

    /** Runs one automation; returns true when any executed action mutated the record. */
    private boolean runAutomation(Automation automation, RecordDocument record) {

        List<AutomationCondition> conditions =
                conditionRepository.findByAutomationId(automation.getId());

        for (AutomationCondition condition : conditions) {

            ConditionEvaluator evaluator = evaluatorMap.get(condition.getOperator());

            if (evaluator == null) {
                throw new IllegalStateException(
                        "No evaluator found for operator : " + condition.getOperator()
                );
            }

            if (!evaluator.evaluate(condition, record.getData())) {
                return false;
            }
        }

        boolean modified = false;

        /*
         * Single-table rule first: the automation row can carry its own action
         * (actionType + actionField/value/email columns). Older data that still
         * keeps rows in automation_actions works exactly as before.
         */
        if (automation.getActionType() != null) {
            modified = executeOne(toInlineAction(automation), record);
        } else {
            List<AutomationAction> actions =
                    actionRepository.findByAutomationIdOrderByExecutionOrderAsc(automation.getId());

            for (AutomationAction action : actions) {
                modified |= executeOne(action, record);
            }
        }

        return modified;
    }

    private boolean executeOne(AutomationAction action, RecordDocument record) {

        ActionExecutor executor = executorMap.get(action.getActionType());

        if (executor == null) {
            throw new IllegalStateException("No executor found for action : " + action.getActionType());
        }

        executor.execute(action, record);
        return executor.mutatesRecord();
    }

    /** Adapts the automation's inline action columns to the executors' input type. */
    private AutomationAction toInlineAction(Automation automation) {
        return AutomationAction.builder()
                .automation(automation)
                .actionType(automation.getActionType())
                .formField(automation.getActionField())
                .value(automation.getActionValue())
                .emailSubject(automation.getEmailSubject())
                .emailMessage(automation.getEmailMessage())
                .documentId(automation.getDocumentId())
                .channel(automation.getChannel())
                .executionOrder(1)
                .build();
    }
}
