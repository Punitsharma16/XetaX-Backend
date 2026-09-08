package com.xetax.crm.invoice;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One payment received against an invoice (part-payments welcome). */
@Entity
@Table(name = "invoice_payments", indexes = @Index(name = "idx_payment_invoice", columnList = "invoiceId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoicePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long invoiceId;

    @Column(nullable = false, length = 64)
    private String ownerUserId;

    @Column(nullable = false)
    private double amount;

    @Column(nullable = false)
    private LocalDate paidOn;

    /** CASH | UPI | BANK | CARD | CHEQUE | OTHER */
    @Column(nullable = false, length = 16)
    private String mode;

    @Column(length = 120)
    private String reference;

    @Column(length = 300)
    private String note;

    private LocalDateTime createdAt;
}
