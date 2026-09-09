package com.xetax.crm.playbook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SalesPlaybookRepository extends JpaRepository<SalesPlaybook, Long> {
    Optional<SalesPlaybook> findByOwnerUserIdAndFormId(String ownerUserId, Long formId);
    Optional<SalesPlaybook> findByIdAndOwnerUserId(Long id, String ownerUserId);
    Optional<SalesPlaybook> findFirstByFormId(Long formId);
    List<SalesPlaybook> findByOwnerUserIdOrderByIdDesc(String ownerUserId);
    List<SalesPlaybook> findByActiveTrue();
}
