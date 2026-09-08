package com.xetax.crm.invoice;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByIdAndOwnerUserId(Long id, String ownerUserId);

    long countByOwnerUserIdAndNumberStartingWith(String ownerUserId, String prefix);

    List<Invoice> findByOwnerUserIdAndContactIdOrderByIdDesc(String ownerUserId, Long contactId);

    List<Invoice> findByOwnerUserIdAndRecordIdOrderByIdDesc(String ownerUserId, String recordId);

    /** History page — every filter optional, LIKE on number/customer. */
    @Query("""
        SELECT i FROM Invoice i
        WHERE i.ownerUserId = :owner
          AND (:status IS NULL OR i.status = :status)
          AND (:q IS NULL OR LOWER(i.number) LIKE LOWER(CONCAT('%', :q, '%'))
                          OR LOWER(i.customerName) LIKE LOWER(CONCAT('%', :q, '%')))
          AND (:fromDate IS NULL OR i.issueDate >= :fromDate)
          AND (:toDate IS NULL OR i.issueDate <= :toDate)
        ORDER BY i.id DESC""")
    Page<Invoice> search(@Param("owner") String owner,
                         @Param("status") String status,
                         @Param("q") String q,
                         @Param("fromDate") java.time.LocalDate fromDate,
                         @Param("toDate") java.time.LocalDate toDate,
                         Pageable pageable);

    List<Invoice> findByOwnerUserId(String ownerUserId);
}

interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, Long> {
    List<InvoicePayment> findByInvoiceIdOrderByIdDesc(Long invoiceId);
    void deleteByInvoiceId(Long invoiceId);
}
