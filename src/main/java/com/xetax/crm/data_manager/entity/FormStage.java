package com.xetax.crm.data_manager.entity;

import com.xetax.crm.data_manager.enums.StageStatus;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "form_stages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long formId;

    @Column(nullable = false)
    private String name;

    private String color;

    private Integer sequence;

    @Column(name = "is_default")
    private Boolean isDefault;

    @Column(name = "is_final")
    private Boolean isFinal;

    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StageStatus status;
}
