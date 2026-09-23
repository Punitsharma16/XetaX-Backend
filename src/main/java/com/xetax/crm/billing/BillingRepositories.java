package com.xetax.crm.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface AiAgentUsageRepository extends JpaRepository<AiAgentUsage, Long> {
    Optional<AiAgentUsage> findByAgentIdAndYearMonth(Long agentId, int yearMonth);
    List<AiAgentUsage> findByOwnerUserIdAndYearMonthOrderByMessagesDesc(String ownerUserId, int yearMonth);
}

interface AiTopupRepository extends JpaRepository<AiTopup, Long> {
    Optional<AiTopup> findByRazorpayOrderId(String razorpayOrderId);
    /**
     * The top-ups worth showing someone, newest first.
     *
     * <p>Filtered inside the query on purpose. Every click on Buy writes a
     * CREATED row whether or not the customer goes through with the payment,
     * so taking the last 20 rows of any status and filtering afterwards showed
     * nothing at all to someone who had opened and closed the payment sheet
     * twenty times — their real purchases were pushed out of the window by
     * their own abandoned attempts.
     *
     * <p>CREATED is left out for the same reason: an opened payment sheet is
     * not something that happened to the customer. FAILED is kept, because a
     * payment that was attempted and rejected is exactly what they come
     * looking for.
     */
    List<AiTopup> findTop20ByOwnerUserIdAndStatusInOrderByIdDesc(String ownerUserId,
                                                                 Collection<String> statuses);
}
