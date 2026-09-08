package com.xetax.crm.contact;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {

    long deleteByCreatedAtBefore(java.time.LocalDateTime cutoff);

    List<EmailLog> findTop50ByOwnerUserIdAndToEmailIgnoreCaseOrderByIdDesc(String ownerUserId, String toEmail);
}
