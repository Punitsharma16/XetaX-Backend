package com.xetax.crm.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentFileRepository extends JpaRepository<DocumentFile, Long> {

    List<DocumentFile> findByOwnerUserIdOrderByIdDesc(String ownerUserId);

    Optional<DocumentFile> findByIdAndOwnerUserId(Long id, String ownerUserId);
}
