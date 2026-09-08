package com.xetax.crm.contact;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    Page<Contact> findByOwnerUserIdOrderByNameAsc(String ownerUserId, Pageable pageable);

    @Query("SELECT c FROM Contact c WHERE c.ownerUserId = :owner AND ("
            + "LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR c.phone LIKE CONCAT('%', :q, '%') "
            + "OR LOWER(c.email) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR LOWER(c.company) LIKE LOWER(CONCAT('%', :q, '%'))) ORDER BY c.name ASC")
    Page<Contact> search(@Param("owner") String owner, @Param("q") String query, Pageable pageable);

    Optional<Contact> findByIdAndOwnerUserId(Long id, String ownerUserId);

    List<Contact> findByIdInAndOwnerUserId(List<Long> ids, String ownerUserId);

    long countByOwnerUserId(String ownerUserId);

    List<Contact> findAllByOwnerUserIdOrderByNameAsc(String ownerUserId);

    List<Contact> findTop5ByOwnerUserIdAndPhone(String ownerUserId, String phone);

    List<Contact> findTop5ByOwnerUserIdAndEmailIgnoreCase(String ownerUserId, String email);

    boolean existsByOwnerUserIdAndPhone(String ownerUserId, String phone);

    boolean existsByOwnerUserIdAndEmailIgnoreCase(String ownerUserId, String email);
}
