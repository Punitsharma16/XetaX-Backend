package com.xetax.crm.data_manager.entity;

import com.xetax.crm.data_manager.enums.FieldType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "form_fields",
        // field_key sirf apne form ke andar unique hai — globally nahi
        // (purana global unique index templates ko block karta tha)
        uniqueConstraints = @UniqueConstraint(name = "uk_form_field", columnNames = {"form_id", "field_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long formId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String fieldKey;

    @Enumerated(EnumType.STRING)
    private FieldType fieldType;

    private Boolean required;
    private Boolean uniqueField;
    private String placeholder;
    private String defaultValue;

    @Column(columnDefinition = "TEXT")
    private String validationJson;

    @Column(columnDefinition = "TEXT")
    private String optionsJson;

    private Integer displayOrder;
    private Boolean searchable;
    private Boolean filterable;
    private Boolean sortable;
    private Boolean hidden;
}
