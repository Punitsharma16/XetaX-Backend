package com.xetax.crm.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface OrgPlanRepository extends JpaRepository<OrgPlan, Long> {
    Optional<OrgPlan> findByOwnerUserId(String ownerUserId);
}

interface AiUsageMonthRepository extends JpaRepository<AiUsageMonth, Long> {
    Optional<AiUsageMonth> findByOwnerUserIdAndYearMonth(String ownerUserId, int yearMonth);
}

interface AiAgentUsageRepository extends JpaRepository<AiAgentUsage, Long> {
    Optional<AiAgentUsage> findByAgentIdAndYearMonth(Long agentId, int yearMonth);
    List<AiAgentUsage> findByOwnerUserIdAndYearMonthOrderByMessagesDesc(String ownerUserId, int yearMonth);
}

interface AiTopupRepository extends JpaRepository<AiTopup, Long> {
    Optional<AiTopup> findByRazorpayOrderId(String razorpayOrderId);
    List<AiTopup> findTop20ByOwnerUserIdOrderByIdDesc(String ownerUserId);
}

interface OrgSubscriptionRepository extends JpaRepository<OrgSubscription, Long> {
    Optional<OrgSubscription> findFirstByOwnerUserIdAndStatusOrderByIdDesc(String ownerUserId, String status);
    List<OrgSubscription> findTop20ByOwnerUserIdOrderByIdDesc(String ownerUserId);
    List<OrgSubscription> findByStatus(String status);
}
