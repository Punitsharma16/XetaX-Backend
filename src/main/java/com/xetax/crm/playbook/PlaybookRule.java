package com.xetax.crm.playbook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * One playbook rule = trigger + filters + action. Stored as JSON on the
 * playbook so new triggers/actions are additive and never need a migration.
 *
 * Triggers: NO_REPLY (customer silent for afterMinutes), STAGE_IDLE (sitting
 * in one of stageIds for afterMinutes), RECORD_CREATED (afterMinutes after
 * creation), QUALIFIED (all qualification fields filled), INTEREST (AI summary
 * says HOT / WARM / COLD).
 *
 * Actions: SEND_MESSAGE, SEND_DOCUMENT, MOVE_STAGE, CREATE_TASK, HANDOFF, NOTIFY.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlaybookRule {

    public static final List<String> TRIGGERS = List.of("NO_REPLY", "STAGE_IDLE", "RECORD_CREATED", "QUALIFIED", "INTEREST");
    public static final List<String> ACTIONS = List.of("SEND_MESSAGE", "SEND_DOCUMENT", "MOVE_STAGE", "CREATE_TASK", "HANDOFF", "NOTIFY");
    public static final List<String> OPS = List.of("EQUALS", "NOT_EQUALS", "CONTAINS", "BLANK", "NOT_BLANK", "GT", "LT");

    private String id;
    private String name;
    private String trigger;
    /** Delay for time-based triggers, minutes. */
    private Integer afterMinutes;
    /** Only records currently in these stages (empty = any non-final stage). */
    private List<Long> stageIds = new ArrayList<>();
    /** INTEREST trigger: HOT | WARM | COLD. */
    private String interest;
    /** How many times this rule may fire per record (default 1). */
    private Integer maxRepeats;
    /** Gap between repeats, minutes (default = afterMinutes). */
    private Integer repeatEveryMinutes;

    private String action;
    /** SEND_MESSAGE / SEND_DOCUMENT caption / CREATE_TASK notes — {fieldKey} placeholders allowed. */
    private String message;
    /** Let the agent write the message from the goal + record instead of the static text. */
    private boolean aiCompose;
    /** Approved WhatsApp template used when the 24h window is closed (optional). */
    private String templateName;
    /** Field keys filled into the template's {{1}}, {{2}}… body params (default: the name field). */
    private List<String> templateParams = new ArrayList<>();
    /** SEND_DOCUMENT: overrides the playbook's quotation document. */
    private Long documentId;
    /** MOVE_STAGE target. */
    private Long targetStageId;
    /** CREATE_TASK / HANDOFF: task title. */
    private String taskTitle;
    private Integer taskDueHours;
    private boolean active = true;
    private List<Condition> conditions = new ArrayList<>();

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Condition {
        private String fieldKey;
        /** EQUALS | NOT_EQUALS | CONTAINS | BLANK | NOT_BLANK | GT | LT */
        private String op;
        private String value;
    }
}
