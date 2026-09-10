package com.xetax.crm.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface AiAgentUsageRepository extends JpaRepository<AiAgentUsage, Long> {
    Optional<AiAgentUsage> findByAgentIdAndYearMonth(Long agentId, int yearMonth);
    List<AiAgentUsage> findByOwnerUserIdAndYearMonthOrderByMessagesDesc(String ownerUserId, int yearMonth);
}

interface AiTopupRepository extends JpaRepository<AiTopup, Long> {
    Optional<AiTopup> findByRazorpayOrderId(String razorpayOrderId);
    List<AiTopup> findTop20ByOwnerUserIdOrderByIdDesc(String ownerUserId);
}
