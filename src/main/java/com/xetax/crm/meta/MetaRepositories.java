package com.xetax.crm.meta;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

interface MetaConnectionRepository extends JpaRepository<MetaConnection, Long> {
    List<MetaConnection> findByOwnerUserIdOrderByIdDesc(String ownerUserId);
    Optional<MetaConnection> findByOwnerUserIdAndPageId(String ownerUserId, String pageId);
    Optional<MetaConnection> findByIdAndOwnerUserId(Long id, String ownerUserId);
    Optional<MetaConnection> findFirstByPageIdAndStatus(String pageId, String status);
    List<MetaConnection> findByStatus(String status);
}

interface MetaLeadAttributionRepository extends JpaRepository<MetaLeadAttribution, Long> {
    boolean existsByLeadgenId(String leadgenId);
    Optional<MetaLeadAttribution> findFirstByRecordId(String recordId);
    List<MetaLeadAttribution> findTop50ByOwnerUserIdOrderByIdDesc(String ownerUserId);

    @Query("""
            select a.campaignId as campaignId, a.campaignName as campaignName,
                   count(a.id) as leads
              from MetaLeadAttribution a
             where a.ownerUserId = :owner and a.createdAt >= :from and a.createdAt < :to
             group by a.campaignId, a.campaignName
            """)
    List<CampaignLeadCount> countLeadsByCampaign(@Param("owner") String owner,
                                                 @Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);

    List<MetaLeadAttribution> findByOwnerUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            String ownerUserId, LocalDateTime from, LocalDateTime to);

    interface CampaignLeadCount {
        String getCampaignId();
        String getCampaignName();
        long getLeads();
    }
}

interface MetaAdDailyRepository extends JpaRepository<MetaAdDaily, Long> {
    Optional<MetaAdDaily> findByOwnerUserIdAndDayAndAdId(String ownerUserId, LocalDate day, String adId);
    List<MetaAdDaily> findByOwnerUserIdAndDayGreaterThanEqualAndDayLessThanEqual(
            String ownerUserId, LocalDate from, LocalDate to);
}
