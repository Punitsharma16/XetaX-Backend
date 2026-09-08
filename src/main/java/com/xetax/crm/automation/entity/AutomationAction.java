package com.xetax.crm.automation.entity;

import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.data_manager.entity.BaseEntity;
import com.xetax.crm.data_manager.entity.FormField;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "automation_actions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationAction extends BaseEntity {


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "automation_id", nullable = false)
    private Automation automation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AutomationActionType actionType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_field_id")
    private FormField formField;

    @Column(length = 2000)
    private String value;

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

    @Column(nullable = false)
    private Integer executionOrder;
}
