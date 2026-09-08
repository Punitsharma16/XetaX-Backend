package com.xetax.crm.invoice;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One invoice. Belongs to a contact OR a record (a record only once it sits in
 * a FINAL stage — an unfinished deal has nothing to bill). All money math is
 * done server-side from the items; the client only ever sends items + rates.
 */
@Entity
@Table(name = "invoices",
        uniqueConstraints = @UniqueConstraint(columnNames = {"ownerUserId", "number"}),
        indexes = {
                @Index(name = "idx_invoice_owner", columnList = "ownerUserId, status"),
                @Index(name = "idx_invoice_contact", columnList = "contactId"),
                @Index(name = "idx_invoice_record", columnList = "recordId")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String ownerUserId;

    /** INV-2026-0001 — per-org, per-year sequence. */
    @Column(nullable = false, length = 32)
    private String number;

    private Long contactId;

    @Column(length = 64)
    private String recordId;

    // Customer snapshot — frozen at creation so later edits to the
    // contact/record never rewrite an issued invoice.
    @Column(nullable = false, length = 160)
    private String customerName;

    @Column(length = 32)
    private String customerPhone;

    @Column(length = 160)
    private String customerEmail;

    @Column(length = 500)
    private String customerAddress;

    /** DRAFT | SENT | PARTIAL | PAID | CANCELLED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private LocalDate issueDate;

    private LocalDate dueDate;

    @Column(nullable = false)
    private double subtotal;

    @Column(nullable = false)
    private double taxPercent;

    @Column(nullable = false)
    private double taxAmount;

    @Column(nullable = false)
    private double discount;

    @Column(nullable = false)
    private double total;

    @Column(nullable = false)
    private double amountPaid;

    @Column(length = 1000)
    private String notes;

    // ---- Tally-style extras (all optional — old invoices stay valid) ----

    /** Buyer's GSTIN, printed under Bill To. */
    @Column(length = 20)
    private String customerGstin;

    /** Seller's GSTIN, printed under the letterhead. */
    @Column(length = 20)
    private String sellerGstin;

    /** NONE | INTRA (CGST+SGST split) | INTER (IGST). Display split only —
     *  the stored taxAmount is always the full tax. */
    @Column(length = 8)
    private String gstMode;

    /** Rounding applied to reach a whole-rupee total (can be negative). */
    @Column(nullable = false)
    private double roundOff;

    /** Bank / UPI details block printed on the PDF. */
    @Column(length = 500)
    private String bankDetails;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    @Builder.Default
    private List<InvoiceItem> items = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
