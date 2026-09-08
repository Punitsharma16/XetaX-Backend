package com.xetax.crm.invoice;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

/** One line on an invoice — amount is always qty × unitPrice, server-computed. */
@Entity
@Table(name = "invoice_items", indexes = @Index(name = "idx_item_invoice", columnList = "invoice_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    @JsonIgnore
    private Invoice invoice;

    @Column(nullable = false, length = 300)
    private String description;

    /** HSN/SAC code — optional, printed on the invoice. */
    @Column(length = 16)
    private String hsn;

    /** Nos / Pcs / Kg / Box / Hrs … free text, defaults to "Nos". */
    @Column(length = 12)
    private String unit;

    @Column(nullable = false)
    private double quantity;

    @Column(nullable = false)
    private double unitPrice;

    @Column(nullable = false)
    private double amount;

    @Column(nullable = false)
    private int sortOrder;
}
