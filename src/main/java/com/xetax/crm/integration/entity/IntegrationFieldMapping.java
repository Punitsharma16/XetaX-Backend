package com.xetax.crm.integration.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import com.xetax.crm.data_manager.entity.FormField;
import jakarta.persistence.*;
import lombok.*;


@Entity
@Table(
        name = "integration_field_mappings",
        uniqueConstraints = {
                @UniqueConstraint(
                        columnNames = {
                                "integration_id",
                                "source_field"
                        }
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntegrationFieldMapping extends BaseEntity {

    /*
     * The identifier comes from BaseEntity (@Id + GenerationType.IDENTITY).
     * Re-declaring it here made Hibernate reject the entity:
     *   "Attribute 'id' is declared as an '@Id' ... and so ... may not
     *    respecify the generation strategy"
     * Behaviour is unchanged — same column, same identity strategy.
     */

    /**
     * Which integration
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "integration_id", nullable = false)
    private Integration integration;

    /**
     * Incoming JSON field
     *
     * Example
     * mobile
     * customer_name
     * body
     */
    @Column(nullable = false)
    private String sourceField;

    /**
     * CRM Dynamic Field
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_field_id", nullable = false)
    private FormField formField;

}
