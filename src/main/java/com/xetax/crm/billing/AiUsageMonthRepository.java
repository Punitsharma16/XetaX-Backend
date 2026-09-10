package com.xetax.crm.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Public because the platform console reads it across every workspace. */
public interface AiUsageMonthRepository extends JpaRepository<AiUsageMonth, Long> {
    Optional<AiUsageMonth> findByOwnerUserIdAndYearMonth(String ownerUserId, int yearMonth);
}
