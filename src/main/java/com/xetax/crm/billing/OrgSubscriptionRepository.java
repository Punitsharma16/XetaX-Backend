package com.xetax.crm.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Public because the platform console reads it across every workspace. */
public interface OrgSubscriptionRepository extends JpaRepository<OrgSubscription, Long> {
    Optional<OrgSubscription> findFirstByOwnerUserIdAndStatusOrderByIdDesc(String ownerUserId, String status);
    List<OrgSubscription> findTop20ByOwnerUserIdOrderByIdDesc(String ownerUserId);
    List<OrgSubscription> findByStatus(String status);
}
