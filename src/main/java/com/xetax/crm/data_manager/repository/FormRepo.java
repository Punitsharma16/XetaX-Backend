package com.xetax.crm.data_manager.repository;

import com.xetax.crm.data_manager.entity.FormEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FormRepo extends JpaRepository<FormEntity , Long> {

    Optional<FormEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<FormEntity> findByOwnerUserId(String ownerUserId);

    Optional<FormEntity> findByPublicKey(String publicKey);
}
