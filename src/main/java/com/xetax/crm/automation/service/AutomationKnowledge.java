package com.xetax.crm.automation.service;

import com.xetax.crm.automation.entity.Automation;
import com.xetax.crm.automation.entity.AutomationCondition;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the semantic-index text for an automation rule. Shared by
 * AutomationServiceImpl (create/update/delete) and
 * AutomationConditionServiceImpl (condition save = rule configuration
 * update), so both always produce identical content for the same state.
 */
final class AutomationKnowledge {

    static final String MODULE = "automation";

    private AutomationKnowledge() {
    }

    /**
     * @param stageName resolved trigger-stage name, or null when the rule has
     *                  no trigger stage (caller resolves it — this class does
     *                  no repository access)
     */
    static String content(Automation automation, List<AutomationCondition> conditions, String stageName) {
        StringBuilder sb = new StringBuilder();
        sb.append("CRM Automation rule \"").append(automation.getName())
                .append("\" on form \"").append(automation.getForm().getName()).append("\".");
        if (automation.getDescription() != null && !automation.getDescription().isBlank()) {
            sb.append(" Description: ").append(automation.getDescription()).append(".");
        }
        sb.append(" Trigger: ").append(automation.getTrigger());
        if (automation.getTriggerStageId() != null) {
            sb.append(" (on stage \"")
                    .append(stageName != null ? stageName : automation.getTriggerStageId())
                    .append("\")");
        }
        sb.append(".");
        if (conditions != null && !conditions.isEmpty()) {
            sb.append(" Conditions: ")
                    .append(conditions.stream()
                            .map(c -> c.getFormField().getLabel() + " "
                                    + c.getOperator() + " \"" + c.getValue() + "\"")
                            .collect(Collectors.joining(" AND ")))
                    .append(".");
        }
        if (automation.getActionType() != null) {
            sb.append(" Action: ").append(automation.getActionType());
            if (automation.getActionField() != null) {
                sb.append(" using field \"").append(automation.getActionField().getLabel()).append("\"");
            }
            if (automation.getActionValue() != null && !automation.getActionValue().isBlank()) {
                sb.append(", value: ").append(automation.getActionValue());
            }
            sb.append(".");
        }
        if (automation.getEmailSubject() != null && !automation.getEmailSubject().isBlank()) {
            sb.append(" Email subject: ").append(automation.getEmailSubject()).append(".");
        }
        sb.append(" Active: ").append(Boolean.TRUE.equals(automation.getActive()) ? "yes" : "no").append(".");
        return sb.toString();
    }
}
