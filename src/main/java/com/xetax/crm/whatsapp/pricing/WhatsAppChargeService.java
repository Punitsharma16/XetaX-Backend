package com.xetax.crm.whatsapp.pricing;

import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.whatsapp.entity.WhatsAppCampaign;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppTemplate;
import com.xetax.crm.whatsapp.enums.RecipientStatus;
import com.xetax.crm.whatsapp.repository.WhatsAppCampaignRecipientRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppCampaignRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppMessageRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppTemplateRepository;
import com.xetax.crm.whatsapp.service.WhatsAppConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/**
 * XetaX's own estimate of the WhatsApp bill — for the month so far, and for a
 * campaign before it starts. Rates come from our table; the rules live in
 * {@link ChargeEstimator}.
 */
@Service
@RequiredArgsConstructor
public class WhatsAppChargeService {

    /** Messages delivered this long after they were queued still land in the month. */
    private static final Duration LOOK_BACK = Duration.ofDays(4);

    private final WhatsAppMessageRepository messageRepository;
    private final WhatsAppTemplateRepository templateRepository;
    private final WhatsAppCampaignRepository campaignRepository;
    private final WhatsAppCampaignRecipientRepository recipientRepository;
    private final WhatsAppRateService rateService;
    private final WhatsAppConfigService configService;

    /** Meta resets monthly at midnight in the WABA's time zone — Indian accounts use IST. */
    @Value("${app.whatsapp-billing-zone:Asia/Kolkata}")
    private String billingZone;

    /** Service messages Meta leaves uncharged each month; 0 until Meta confirms an allowance. */
    @Value("${app.whatsapp-free-service-per-month:0}")
    private int freeServicePerMonth;

    public ZoneId zone() {
        try {
            return ZoneId.of(billingZone);
        } catch (Exception e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }

    /* -------------------------------------------------------- the month */

    public Map<String, Object> monthEstimate(WhatsAppConfig config, YearMonth month) {
        ZoneId zone = zone();
        ZoneId stored = ZoneId.systemDefault();   // the zone createdAt was written in
        LocalDateTime from = month.atDay(1).atStartOfDay(zone).minus(LOOK_BACK)
                .withZoneSameInstant(stored).toLocalDateTime();

        List<ChargeEstimator.Outbound> outbound = new ArrayList<>();
        for (Object[] row : messageRepository.outboundForCharges(config.getId(), from)) {
            Instant created = instant((LocalDateTime) row[10], stored);
            Instant sent = row[7] != null ? (Instant) row[7] : created;
            Instant delivered = (Instant) row[8];
            Instant read = (Instant) row[9];
            outbound.add(new ChargeEstimator.Outbound(
                    (Long) row[0], (Long) row[1], name(row[2]), (String) row[3], (String) row[4],
                    name(row[5]), (String) row[6], sent,
                    delivered != null ? delivered : read != null ? read : null));
        }
        List<ChargeEstimator.Inbound> inbound = new ArrayList<>();
        for (Object[] row : messageRepository.inboundForCharges(config.getId(), from)) {
            Instant time = row[1] != null ? (Instant) row[1] : instant((LocalDateTime) row[2], stored);
            inbound.add(new ChargeEstimator.Inbound((Long) row[0], time, row[3] != null));
        }

        Map<String, String> categories = templateCategories(config);
        ChargeEstimator.Result result = ChargeEstimator.estimate(outbound, inbound,
                out -> categoryOf(categories, out.templateName(), out.templateLanguage()),
                rateService.book(), zone, month, freeServicePerMonth);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("month", month.toString());
        view.put("zone", zone.getId());
        view.put("currency", "INR");
        view.put("total", result.total());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ChargeEstimator.Line line : result.lines()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("category", line.category().name());
            entry.put("messages", line.messages());
            entry.put("amount", line.amount());
            lines.add(entry);
        }
        view.put("lines", lines);
        view.put("chargedMessages", result.chargedMessages());
        view.put("freeEntryPoint", result.freeEntryPoint());
        view.put("freeAllowance", result.freeAllowance());
        view.put("awaitingDelivery", result.awaitingDelivery());
        view.put("otherCountries", result.otherCountries());
        view.put("unknownCategory", result.unknownCategory());
        view.put("unpriced", result.unpriced());
        return view;
    }

    /* ----------------------------------------------------- a campaign */

    /**
     * The most this campaign can cost: every remaining Indian recipient
     * charged at the template's rate on the day it is due to go out. Only
     * delivered messages are charged, so the real bill can only be lower.
     */
    public Map<String, Object> campaignEstimate(Long campaignId) {
        String owner = configService.currentUserId();
        WhatsAppCampaign campaign = campaignRepository.findById(campaignId)
                .filter(c -> owner.equals(c.getOwnerUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found"));
        ZoneId zone = zone();

        List<RecipientStatus> remaining = List.of(RecipientStatus.PENDING, RecipientStatus.QUEUED);
        long recipients = recipientRepository.countByCampaignIdAndStatusIn(campaignId, remaining);
        long india = recipientRepository.countByCampaignIdAndStatusInAndPhoneStartingWith(campaignId, remaining, "91");

        ChargeCategory category;
        String templateCategory = null;
        if (campaign.getTemplateName() != null && !campaign.getTemplateName().isBlank()) {
            WhatsAppConfig config = new WhatsAppConfig();
            config.setId(campaign.getWhatsappConfigId());
            config.setOwnerUserId(owner);
            templateCategory = categoryOf(templateCategories(config),
                    campaign.getTemplateName(), campaign.getTemplateLanguage());
            category = ChargeEstimator.templateLine(templateCategory);
        } else {
            category = ChargeCategory.SERVICE;
        }

        LocalDate today = LocalDate.now(zone);
        LocalDate day = campaign.getScheduledAt() != null
                && campaign.getScheduledAt().atZone(zone).toLocalDate().isAfter(today)
                ? campaign.getScheduledAt().atZone(zone).toLocalDate() : today;
        BigDecimal rate = category == null ? null : rateService.book().rate(category.pricedAs(), day);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("recipients", recipients);
        view.put("india", india);
        view.put("otherCountries", recipients - india);
        view.put("category", category == null ? null : category.name());
        view.put("templateCategory", templateCategory);
        view.put("rate", rate);
        view.put("rateDate", day.toString());
        view.put("amount", rate == null ? null
                : rate.multiply(BigDecimal.valueOf(india)).setScale(2, RoundingMode.HALF_UP));
        view.put("currency", "INR");
        return view;
    }

    /* -------------------------------------------------------- helpers */

    private Map<String, String> templateCategories(WhatsAppConfig config) {
        Map<String, String> out = new HashMap<>();
        for (WhatsAppTemplate template : templateRepository
                .findByOwnerUserIdAndWhatsappConfigIdOrderByNameAsc(config.getOwnerUserId(), config.getId())) {
            if (template.getCategory() == null) continue;
            String category = template.getCategory().trim().toUpperCase(Locale.ROOT);
            out.put(template.getName() + "|" + template.getLanguage(), category);
            out.putIfAbsent(template.getName(), category);
        }
        return out;
    }

    static String categoryOf(Map<String, String> categories, String name, String language) {
        if (name == null) return null;
        String exact = categories.get(name + "|" + language);
        return exact != null ? exact : categories.get(name);
    }

    private static String name(Object value) {
        if (value == null) return null;
        return value instanceof Enum<?> e ? e.name() : value.toString();
    }

    private static Instant instant(LocalDateTime time, ZoneId zone) {
        return time == null ? null : time.atZone(zone).toInstant();
    }
}
