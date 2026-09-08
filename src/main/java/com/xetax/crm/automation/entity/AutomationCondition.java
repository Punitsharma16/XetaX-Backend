package com.xetax.crm.automation.entity;

import com.xetax.crm.automation.enums.ConditionOperator;
import com.xetax.crm.data_manager.entity.BaseEntity;
import com.xetax.crm.data_manager.entity.FormField;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "automation_conditions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationCondition extends BaseEntity {


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "automation_id", nullable = false)
    private Automation automation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_field_id", nullable = false)
    private FormField formField;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConditionOperator operator;

    @Column(nullable = false)
    private String value;
}
