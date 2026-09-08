package com.xetax.crm.integration.repository;

import com.xetax.crm.integration.entity.IntegrationFieldMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntegrationFieldMappingRepository extends JpaRepository<IntegrationFieldMapping, Long> {

    List<IntegrationFieldMapping> findByIntegrationId(Long integrationId);

    void deleteByIntegrationId(Long integrationId);

}
