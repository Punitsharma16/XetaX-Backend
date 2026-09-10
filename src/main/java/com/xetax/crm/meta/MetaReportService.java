package com.xetax.crm.meta;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.repository.RecordRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * The number Ads Manager cannot show: what the spend actually turned into.
 * Spend comes from Meta, the outcome comes from the pipeline, joined on the
 * campaign each lead was attributed to.
 */
@Service
@RequiredArgsConstructor
public class MetaReportService {

    private final MetaConnectionRepository connections;
    private final MetaAdDailyRepository daily;
    private final MetaLeadAttributionRepository attributions;
    private final RecordRepo recordRepo;
    private final CurrentUserProvider currentUserProvider;

    private String owner() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    public Map<String, Object> report(LocalDate from, LocalDate to) {
        String own = owner();
        LocalDate start = from == null ? LocalDate.now().minusDays(29) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        if (end.isBefore(start)) throw new BadRequestException("The end date is before the start date");

        Set<Long> wonStages = new HashSet<>();
        String currency = "INR";
        for (MetaConnection c : connections.findByOwnerUserIdOrderByIdDesc(own)) {
            if (c.getWonStageId() != null) wonStages.add(c.getWonStageId());
            if (c.getCurrency() != null && !c.getCurrency().isBlank()) currency = c.getCurrency();
        }

        // spend side
        Map<String, Row> byCampaign = new LinkedHashMap<>();
        for (MetaAdDaily d : daily.findByOwnerUserIdAndDayGreaterThanEqualAndDayLessThanEqual(own, start, end)) {
            Row row = byCampaign.computeIfAbsent(key(d.getCampaignId()), k -> new Row(d.getCampaignName()));
            if (row.name == null) row.name = d.getCampaignName();
            row.spend = row.spend.add(d.getSpend() == null ? BigDecimal.ZERO : d.getSpend());
            row.impressions += d.getImpressions();
            row.clicks += d.getClicks();
            row.metaLeads += d.getLeads();
        }

        // outcome side
        List<MetaLeadAttribution> leads = attributions
                .findByOwnerUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        own, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        for (MetaLeadAttribution lead : leads) {
            Row row = byCampaign.computeIfAbsent(key(lead.getCampaignId()), k -> new Row(lead.getCampaignName()));
            if (row.name == null) row.name = lead.getCampaignName();
            row.crmLeads++;
            if (lead.getRecordId() == null) continue;
            RecordDocument record = recordRepo.findById(lead.getRecordId()).orElse(null);
            if (record == null) continue;
            if (!wonStages.isEmpty() && wonStages.contains(record.getStageId())) row.won++;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalSpend = BigDecimal.ZERO;
        long totalLeads = 0, totalWon = 0;
        for (Map.Entry<String, Row> entry : byCampaign.entrySet()) {
            Row r = entry.getValue();
            long leadCount = Math.max(r.crmLeads, r.metaLeads);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("campaignId", entry.getKey().equals("-") ? null : entry.getKey());
            m.put("campaign", r.name == null ? "Not from an ad" : r.name);
            m.put("spend", r.spend);
            m.put("impressions", r.impressions);
            m.put("clicks", r.clicks);
            m.put("leads", leadCount);
            m.put("costPerLead", divide(r.spend, leadCount));
            m.put("won", r.won);
            m.put("costPerWon", divide(r.spend, r.won));
            rows.add(m);
            totalSpend = totalSpend.add(r.spend);
            totalLeads += leadCount;
            totalWon += r.won;
        }
        rows.sort((a, b) -> ((BigDecimal) b.get("spend")).compareTo((BigDecimal) a.get("spend")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", start);
        out.put("to", end);
        out.put("currency", currency);
        out.put("wonStageConfigured", !wonStages.isEmpty());
        // A plain HashMap, not Map.of: cost per lead / per sale are deliberately
        // null until there is something to divide by, and Map.of rejects nulls.
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("spend", totalSpend);
        totals.put("leads", totalLeads);
        totals.put("won", totalWon);
        totals.put("costPerLead", divide(totalSpend, totalLeads));
        totals.put("costPerWon", divide(totalSpend, totalWon));
        out.put("totals", totals);
        out.put("campaigns", rows);
        return out;
    }

    public List<Map<String, Object>> recentLeads() {
        String own = owner();
        List<Map<String, Object>> out = new ArrayList<>();
        for (MetaLeadAttribution a : attributions.findTop50ByOwnerUserIdOrderByIdDesc(own)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recordId", a.getRecordId());
            m.put("campaign", a.getCampaignName());
            m.put("ad", a.getAdName());
            m.put("form", a.getMetaFormName());
            m.put("platform", a.getPlatform());
            m.put("at", a.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    private static String key(String campaignId) {
        return campaignId == null || campaignId.isBlank() ? "-" : campaignId;
    }

    private static BigDecimal divide(BigDecimal spend, long count) {
        if (count <= 0 || spend == null || spend.signum() == 0) return null;
        return spend.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private static final class Row {
        private String name;
        private BigDecimal spend = BigDecimal.ZERO;
        private long impressions;
        private long clicks;
        private long metaLeads;
        private long crmLeads;
        private long won;

        private Row(String name) {
            this.name = name;
        }
    }
}
