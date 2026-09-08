package com.xetax.crm.automation.entity;

import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.data_manager.entity.BaseEntity;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "automations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Automation extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    /*
     * "trigger" is a reserved word in MySQL, so the generated DDL failed and the
     * `automations` table was never created. Backticks tell Hibernate to emit a
     * quoted identifier, keeping the column name exactly as before.
     */
    /*
     * Stored as VARCHAR, not a MySQL ENUM. Hibernate would otherwise generate
     * enum('RECORD_CREATED','RECORD_UPDATED'), and because ddl-auto=update never
     * widens an existing enum, adding STAGE_CHANGED failed at runtime with
     * "Data truncated for column 'trigger'". VARCHAR keeps new trigger values a
     * code-only change.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "`trigger`", nullable = false, columnDefinition = "varchar(50)")
    private AutomationTrigger trigger;

    @Column(nullable = false)
    private Boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_id", nullable = false)
    private FormEntity form;

    /*
     * Only meaningful for STAGE_CHANGED automations: the automation fires only
     * when the record lands on THIS stage. Null keeps the old behaviour — fire
     * on every stage move. A plain id (not a ManyToOne) matches how FormStage
     * itself references its form.
     */
    @Column(name = "trigger_stage_id")
    private Long triggerStageId;

    /**
     * Only meaningful for STATUS_CHANGED automations: fire only when the
     * record lands on THIS status (null = any status change).
     */
    private Long triggerStatusId;

    /*
     * Single-table rule: one automation row carries its own action, so the
     * common "event → one action" case needs no join. When actionType is null
     * the engine falls back to the legacy automation_actions rows, keeping old
     * data working.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", columnDefinition = "varchar(50)")
    private AutomationActionType actionType;

    /** Target of the action — the field to update/adjust, or for SEND_EMAIL
        the record field that holds the recipient address. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "action_field_id")
    private FormField actionField;

    /** UPDATE_FIELD: new value · CHANGE_STAGE: stage id · ASSIGN_USER: user id
        · INCREMENT/DECREMENT_FIELD: amount (default 1). */
    @Column(name = "action_value", length = 2000)
    private String actionValue;

    @Column(name = "email_subject", length = 500)
    private String emailSubject;

    /** SEND_EMAIL body; {fieldKey} placeholders resolve from the record. */
    @Column(name = "email_message", length = 4000)
    private String emailMessage;

    /** SEND_DOCUMENT: which stored document to personalize and send. */
    private Long documentId;

    /** SEND_DOCUMENT: WHATSAPP | EMAIL. */
    @Column(length = 16)
    private String channel;
}
