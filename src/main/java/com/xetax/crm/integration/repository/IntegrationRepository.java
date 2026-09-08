package com.xetax.crm.integration.repository;

import com.xetax.crm.integration.entity.Integration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IntegrationRepository extends JpaRepository<Integration , Long> {

    Optional<Integration> findByIntegrationKey(String integrationKey);

    boolean existsByIntegrationKey(String integrationKey);
}
