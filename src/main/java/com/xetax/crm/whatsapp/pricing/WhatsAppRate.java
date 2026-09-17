package com.xetax.crm.whatsapp.pricing;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One price from Meta's rate card, from a date onward. A category's price on
 * any day is the row with the latest effectiveFrom on or before that day, so
 * a future change is simply a row with a future date.
 */
@Entity
@Table(name = "whatsapp_rates",
        uniqueConstraints = @UniqueConstraint(name = "uq_wa_rate",
                columnNames = {"market", "category", "effective_from"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppRate extends BaseEntity {

    /** Recipient country, by Meta's rule — the recipient's calling code decides the rate. */
    @Column(nullable = false, length = 4)
    private String market;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ChargeCategory category;

    /** INR per delivered message. */
    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal rate;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(length = 255)
    private String note;
}
