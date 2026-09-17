package com.xetax.crm.whatsapp.pricing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WhatsAppRateRepository extends JpaRepository<WhatsAppRate, Long> {

    List<WhatsAppRate> findByMarketOrderByCategoryAscEffectiveFromAsc(String market);

    Optional<WhatsAppRate> findByMarketAndCategoryAndEffectiveFrom(
            String market, ChargeCategory category, LocalDate effectiveFrom);
}
