package com.xetax.crm.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.client.MetaWhatsAppClient;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppTemplate;
import com.xetax.crm.whatsapp.enums.MessageDirection;
import com.xetax.crm.whatsapp.repository.WhatsAppMessageRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * "Usage & spend" for the Setup page.
 *
 * <p>Two sources, clearly separated in the result:
 * <ul>
 *   <li><b>counts</b> — our own DB (message rows + template categories), always
 *       available, real-time.</li>
 *   <li><b>spend</b> — Meta's official charged amounts via the WABA analytics
 *       field. Postpaid WABAs have no "balance", so this shows the month's
 *       bill so far — the same number Meta's own dashboard shows. Cached in
 *       Redis for 1h and fail-open: if Meta/token/Redis is unavailable the
 *       card still renders with spendAvailable=false.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppUsageService {

    private static final Duration SPEND_CACHE_TTL = Duration.ofHours(1);

    private final WhatsAppConfigService configService;
    private final WhatsAppMessageRepository messageRepository;
    private final WhatsAppTemplateRepository templateRepository;
    private final MetaWhatsAppClient client;
    private final SecretEncryptionService encryption;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public Map<String, Object> usage() {
        WhatsAppConfig config = configService.requireConnectedConfig();

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", today.getMonth() + " " + today.getYear());
        result.put("counts", dbCounts(config.getOwnerUserId(), monthStart));
        result.put("categories", categoryCounts(config, monthStart));
        result.put("spend", spend(config, monthStart.atZone(zone).toEpochSecond(),
                Instant.now().getEpochSecond()));
        return result;
    }

    /* ------------------------------------------------------------ DB side */

    private Map<String, Object> dbCounts(String owner, LocalDateTime from) {
        Map<String, Long> byStatus = new HashMap<>();
        for (Object[] row : messageRepository.countOutboundByStatusSince(owner, from)) {
            byStatus.put(String.valueOf(row[0]), (Long) row[1]);
        }
        Map<String, Long> byType = new HashMap<>();
        for (Object[] row : messageRepository.countOutboundByTypeSince(owner, from)) {
            byType.put(String.valueOf(row[0]), (Long) row[1]);
        }
        long delivered = byStatus.getOrDefault("DELIVERED", 0L) + byStatus.getOrDefault("READ", 0L);
        long sent = delivered + byStatus.getOrDefault("SENT", 0L);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("sent", sent);
        counts.put("delivered", delivered);
        counts.put("read", byStatus.getOrDefault("READ", 0L));
        counts.put("failed", byStatus.getOrDefault("FAILED", 0L));
        counts.put("queued", byStatus.getOrDefault("QUEUED", 0L));
        counts.put("freeText", byType.getOrDefault("TEXT", 0L));
        counts.put("template", byType.getOrDefault("TEMPLATE", 0L));
        counts.put("inbound", messageRepository
                .countByOwnerUserIdAndDirectionAndCreatedAtGreaterThanEqual(
                        owner, MessageDirection.INBOUND, from));
        return counts;
    }

    /** Template sends grouped into MARKETING/UTILITY/AUTHENTICATION via local template mirror. */
    private List<Map<String, Object>> categoryCounts(WhatsAppConfig config, LocalDateTime from) {
        Map<String, String> nameToCategory = new HashMap<>();
        for (WhatsAppTemplate template : templateRepository
                .findByOwnerUserIdAndWhatsappConfigIdOrderByNameAsc(
                        config.getOwnerUserId(), config.getId())) {
            nameToCategory.put(template.getName(),
                    template.getCategory() == null ? "UNKNOWN" : template.getCategory());
        }
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (Object[] row : messageRepository
                .countOutboundByTemplateSince(config.getOwnerUserId(), from)) {
            String category = nameToCategory.getOrDefault(String.valueOf(row[0]), "UNKNOWN");
            byCategory.merge(category, (Long) row[1], Long::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        byCategory.forEach((category, count) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("category", category);
            entry.put("messages", count);
            out.add(entry);
        });
        return out;
    }

    /* ---------------------------------------------------------- Meta side */

    private Map<String, Object> spend(WhatsAppConfig config, long start, long end) {
        String cacheKey = "xetax:wa:spend:" + config.getId();
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, LinkedHashMap.class);
            }
        } catch (Exception e) {
            log.debug("Spend cache read skipped: {}", e.getMessage());
        }

        Map<String, Object> spend = fetchSpend(config, start, end);

        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(spend), SPEND_CACHE_TTL);
        } catch (Exception e) {
            log.debug("Spend cache write skipped: {}", e.getMessage());
        }
        return spend;
    }

    private Map<String, Object> fetchSpend(WhatsAppConfig config, long start, long end) {
        Map<String, Object> unavailable = new LinkedHashMap<>();
        unavailable.put("available", false);
        unavailable.put("note", "Meta spend data is not available yet — it appears once "
                + "billed messages start flowing on this account.");
        try {
            if (config.getAccessTokenEncrypted() == null) {
                return unavailable;
            }
            String token = encryption.decrypt(config.getAccessTokenEncrypted());
            JsonNode root = client.getSpendAnalytics(config.getWabaId(), token, start, end);

            JsonNode analytics = root.has("pricing_analytics")
                    ? root.path("pricing_analytics")
                    : root.path("conversation_analytics");
            if (analytics.isMissingNode()) {
                return unavailable;
            }

            double total = 0;
            Map<String, Double> byCategory = new LinkedHashMap<>();
            for (JsonNode bucket : analytics.path("data")) {
                for (JsonNode point : bucket.path("data_points")) {
                    double cost = point.path("cost").asDouble(0);
                    total += cost;
                    String category = point.hasNonNull("pricing_category")
                            ? point.path("pricing_category").asText()
                            : point.path("conversation_category").asText("OTHER");
                    byCategory.merge(category, cost, Double::sum);
                }
            }

            Map<String, Object> spend = new LinkedHashMap<>();
            spend.put("available", true);
            spend.put("total", Math.round(total * 100.0) / 100.0);
            spend.put("byCategory", byCategory);
            spend.put("source", root.has("pricing_analytics")
                    ? "meta_pricing_analytics" : "meta_conversation_analytics");
            spend.put("note", "Official Meta charges for this month so far (postpaid accounts "
                    + "are billed monthly — this is the running bill, not a balance).");
            return spend;
        } catch (Exception e) {
            log.info("Meta spend fetch failed: {}", e.getMessage());
            return unavailable;
        }
    }
}
