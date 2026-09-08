package com.xetax.crm.data_manager.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * A sub-state INSIDE one pipeline stage ("Contacted" → Ringing / Call back /
 * Not reachable). Optional per stage — a stage with no statuses behaves
 * exactly like the classic single-level pipeline.
 */
@Entity
@Table(name = "stage_statuses", indexes = {
        @Index(name = "idx_status_stage", columnList = "stageId"),
        @Index(name = "idx_status_form", columnList = "formId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StageStatusOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long stageId;

    @Column(nullable = false)
    private Long formId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 16)
    private String color;

    private Integer sequence;

    /** Applied automatically when a record enters this stage. */
    private Boolean isDefault;
}
