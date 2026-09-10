package com.xetax.crm.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.xetax.crm.whatsapp.service.SecretEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Pulls what each ad spent and delivered, day by day. Meta keeps restating the
 * last few days (attribution windows), so a lookback window is re-pulled every
 * time and rows are overwritten rather than appended.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaInsightsService {

    private final MetaConnectionRepository connections;
    private final MetaAdDailyRepository daily;
    private final MetaGraphClient graph;
    private final SecretEncryptionService encryption;
    private final MetaAdsProperties properties;

    /** Nightly, after Meta has settled the previous day. */
    @Scheduled(cron = "0 40 3 * * *")
    public void nightlySync() {
        for (MetaConnection c : connections.findByStatus(MetaConnection.CONNECTED)) {
            try {
                sync(c, properties.getInsightsLookbackDays());
            } catch (Exception e) {
                log.warn("Ad insights sync failed for page {}: {}", c.getPageId(), e.getMessage());
            }
        }
    }

    @Transactional
    public int sync(MetaConnection connection, int days) {
        if (connection.getAdAccountId() == null || connection.getAdAccountId().isBlank()) return 0;
        if (connection.getUserTokenEncrypted() == null) return 0;
        String token = encryption.decrypt(connection.getUserTokenEncrypted());
        LocalDate until = LocalDate.now();
        LocalDate since = until.minusDays(Math.max(1, days));

        int rows = 0;
        JsonNode page = graph.insights(connection.getAdAccountId(), token, since.toString(), until.toString());
        while (page != null) {
            for (JsonNode row : page.path("data")) {
                store(connection, row);
                rows++;
            }
            String next = page.path("paging").path("next").asText(null);
            page = next == null ? null : graph.next(next, token);
        }
        connection.setLastInsightSyncAt(LocalDateTime.now());
        connections.save(connection);
        return rows;
    }

    private void store(MetaConnection connection, JsonNode row) {
        String adId = row.path("ad_id").asText(null);
        String dayText = row.path("date_start").asText(null);
        if (adId == null || dayText == null) return;
        LocalDate day = LocalDate.parse(dayText);

        MetaAdDaily entity = daily
                .findByOwnerUserIdAndDayAndAdId(connection.getOwnerUserId(), day, adId)
                .orElseGet(() -> MetaAdDaily.builder()
                        .ownerUserId(connection.getOwnerUserId()).day(day).adId(adId).build());
        entity.setAdAccountId(connection.getAdAccountId());
        entity.setCampaignId(row.path("campaign_id").asText(null));
        entity.setCampaignName(row.path("campaign_name").asText(null));
        entity.setAdName(row.path("ad_name").asText(null));
        entity.setSpend(new BigDecimal(row.path("spend").asText("0")));
        entity.setImpressions(row.path("impressions").asLong(0));
        entity.setClicks(row.path("clicks").asLong(0));
        entity.setLeads(leadsOf(row));
        entity.setCurrency(row.path("account_currency").asText(connection.getCurrency()));
        entity.setSyncedAt(LocalDateTime.now());
        daily.save(entity);
    }

    /** Meta reports results in an "actions" array; lead forms show up as a lead action. */
    private static long leadsOf(JsonNode row) {
        long total = 0;
        for (JsonNode action : row.path("actions")) {
            String type = action.path("action_type").asText("");
            if (type.equals("lead") || type.startsWith("leadgen")
                    || type.equals("onsite_conversion.lead_grouped")) {
                total += action.path("value").asLong(0);
            }
        }
        return total;
    }
}
