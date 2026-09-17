package com.xetax.crm.whatsapp.pricing;

import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.whatsapp.entity.WhatsAppCampaign;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppTemplate;
import com.xetax.crm.whatsapp.enums.MessageDirection;
import com.xetax.crm.whatsapp.enums.RecipientStatus;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageStatus;
import com.xetax.crm.whatsapp.enums.WhatsAppMessageType;
import com.xetax.crm.whatsapp.repository.WhatsAppCampaignRecipientRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppCampaignRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppMessageRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppTemplateRepository;
import com.xetax.crm.whatsapp.service.WhatsAppConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The month's estimate and a campaign's estimate, from repository rows shaped as the queries return them. */
class WhatsAppChargeServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private WhatsAppMessageRepository messages;
    private WhatsAppCampaignRepository campaigns;
    private WhatsAppCampaignRecipientRepository recipients;
    private WhatsAppChargeService service;
    private final WhatsAppConfig config = new WhatsAppConfig();

    @BeforeEach
    void setUp() {
        messages = mock(WhatsAppMessageRepository.class);
        campaigns = mock(WhatsAppCampaignRepository.class);
        recipients = mock(WhatsAppCampaignRecipientRepository.class);

        WhatsAppTemplateRepository templates = mock(WhatsAppTemplateRepository.class);
        when(templates.findByOwnerUserIdAndWhatsappConfigIdOrderByNameAsc("owner-1", 7L)).thenReturn(List.of(
                template("promo", "en", "MARKETING"),
                template("order_update", "en", "UTILITY"),
                template("order_update", "hi", "MARKETING")));   // same name, other language, other category

        WhatsAppRateRepository rateRepository = mock(WhatsAppRateRepository.class);
        when(rateRepository.findByMarketOrderByCategoryAscEffectiveFromAsc("IN")).thenReturn(List.of(
                rate(ChargeCategory.MARKETING, "0.8631", "2025-07-01"),
                rate(ChargeCategory.UTILITY, "0.1150", "2025-07-01"),
                rate(ChargeCategory.AUTHENTICATION, "0.1150", "2025-07-01"),
                rate(ChargeCategory.MARKETING, "0.9000", "2026-11-01")));
        WhatsAppRateService rates = new WhatsAppRateService(rateRepository);

        WhatsAppConfigService configService = mock(WhatsAppConfigService.class);
        when(configService.currentUserId()).thenReturn("owner-1");

        service = new WhatsAppChargeService(messages, templates, campaigns, recipients, rates, configService);
        ReflectionTestUtils.setField(service, "billingZone", "Asia/Kolkata");
        ReflectionTestUtils.setField(service, "freeServicePerMonth", 0);

        config.setId(7L);
        config.setOwnerUserId("owner-1");
    }

    private static WhatsAppTemplate template(String name, String language, String category) {
        WhatsAppTemplate t = new WhatsAppTemplate();
        t.setName(name);
        t.setLanguage(language);
        t.setCategory(category);
        return t;
    }

    private static WhatsAppRate rate(ChargeCategory category, String value, String from) {
        return WhatsAppRate.builder().market("IN").category(category)
                .rate(new BigDecimal(value)).effectiveFrom(LocalDate.parse(from)).build();
    }

    private static Instant ist(String at) {
        return LocalDateTime.parse(at).atZone(IST).toInstant();
    }

    /** Exactly the column order and Java types outboundForCharges returns. */
    private static Object[] out(long id, WhatsAppMessageType type, String template, String language,
                                WhatsAppMessageStatus status, Instant sent, Instant delivered, Instant read) {
        return new Object[]{id, 1L, type, template, language, status, "919034908543", sent, delivered, read,
                LocalDateTime.ofInstant(sent == null ? ist("2026-10-02T09:00") : sent, ZoneId.systemDefault())};
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMonthIsEstimatedFromTheRows() {
        Instant t = ist("2026-10-02T10:00");
        when(messages.outboundForCharges(eq(7L), any())).thenReturn(List.of(
                out(1, WhatsAppMessageType.TEMPLATE, "promo", "en", WhatsAppMessageStatus.DELIVERED, t, t, null),
                out(2, WhatsAppMessageType.TEMPLATE, "order_update", "en", WhatsAppMessageStatus.READ, t, null, t),
                out(3, WhatsAppMessageType.TEMPLATE, "order_update", "hi", WhatsAppMessageStatus.DELIVERED, t, t, null),
                out(4, WhatsAppMessageType.TEXT, null, null, WhatsAppMessageStatus.DELIVERED, t, t, null),
                out(5, WhatsAppMessageType.TEXT, null, null, WhatsAppMessageStatus.SENT, t, null, null),
                out(6, WhatsAppMessageType.TEXT, null, null, WhatsAppMessageStatus.FAILED, t, null, null),
                out(7, WhatsAppMessageType.TEXT, null, null, WhatsAppMessageStatus.DELIVERED, null, t, null)));
        when(messages.inboundForCharges(eq(7L), any())).thenReturn(List.of());

        Map<String, Object> view = service.monthEstimate(config, YearMonth.of(2026, 10));

        List<Map<String, Object>> lines = (List<Map<String, Object>>) view.get("lines");
        Map<String, Map<String, Object>> byCategory = new HashMap<>();
        lines.forEach(l -> byCategory.put((String) l.get("category"), l));
        assertEquals(2L, byCategory.get("MARKETING").get("messages"), "promo + the Hindi order_update, which is marketing");
        assertEquals(1L, byCategory.get("UTILITY").get("messages"), "read without a delivery receipt still counts");
        assertEquals(2L, byCategory.get("SERVICE").get("messages"), "a reply with no sentAt falls back to createdAt");
        // 2 × 0.8631 + 0.1150 + 2 × 0.1150 = 2.0712
        assertEquals(new BigDecimal("2.07"), view.get("total"));
        assertEquals(1L, view.get("awaitingDelivery"));
        assertEquals("Asia/Kolkata", view.get("zone"));
        assertEquals("2026-10", view.get("month"));
    }

    @Test
    void theQueryLooksBackFarEnoughForLateDeliveries() {
        when(messages.outboundForCharges(eq(7L), any())).thenReturn(List.of());
        when(messages.inboundForCharges(eq(7L), any())).thenReturn(List.of());
        service.monthEstimate(config, YearMonth.of(2026, 10));

        var captor = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(messages).outboundForCharges(eq(7L), captor.capture());
        LocalDateTime from = captor.getValue();
        Instant monthStart = ist("2026-10-01T00:00");
        Instant fromInstant = from.atZone(ZoneId.systemDefault()).toInstant();
        assertEquals(Duration.ofDays(4), Duration.between(fromInstant, monthStart));
    }

    @Test
    void anInboundRowFromAnAdOpensAFreeWindow() {
        Instant click = ist("2026-10-02T10:00");
        Instant replied = ist("2026-10-02T10:30");
        when(messages.outboundForCharges(eq(7L), any())).thenReturn(List.<Object[]>of(
                out(1, WhatsAppMessageType.TEXT, null, null, WhatsAppMessageStatus.DELIVERED, replied, replied, null)));
        when(messages.inboundForCharges(eq(7L), any())).thenReturn(List.<Object[]>of(
                new Object[]{1L, click, LocalDateTime.now(), "ad"}));

        Map<String, Object> view = service.monthEstimate(config, YearMonth.of(2026, 10));
        assertEquals(1L, view.get("freeEntryPoint"));
        assertEquals(new BigDecimal("0.00"), view.get("total"));
    }

    /* ------------------------------------------------------ campaigns */

    private WhatsAppCampaign campaign(String template, String language, Instant scheduledAt) {
        WhatsAppCampaign c = new WhatsAppCampaign();
        c.setId(3L);
        c.setOwnerUserId("owner-1");
        c.setWhatsappConfigId(7L);
        c.setTemplateName(template);
        c.setTemplateLanguage(language);
        c.setScheduledAt(scheduledAt);
        when(campaigns.findById(3L)).thenReturn(Optional.of(c));
        List<RecipientStatus> remaining = List.of(RecipientStatus.PENDING, RecipientStatus.QUEUED);
        when(recipients.countByCampaignIdAndStatusIn(3L, remaining)).thenReturn(1000L);
        when(recipients.countByCampaignIdAndStatusInAndPhoneStartingWith(3L, remaining, "91")).thenReturn(990L);
        return c;
    }

    @Test
    void aMarketingCampaignCostsItsIndianRecipientsAtTheMarketingRate() {
        campaign("promo", "en", null);
        Map<String, Object> view = service.campaignEstimate(3L);
        assertEquals(1000L, view.get("recipients"));
        assertEquals(990L, view.get("india"));
        assertEquals(10L, view.get("otherCountries"));
        assertEquals("MARKETING", view.get("category"));
        assertEquals(new BigDecimal("0.8631"), view.get("rate"));
        assertEquals(new BigDecimal("854.47"), view.get("amount"));   // 990 × 0.8631 = 854.469
    }

    @Test
    void aScheduledCampaignIsPricedOnTheDayItGoesOut() {
        campaign("promo", "en", Instant.now().plus(Duration.ofDays(3650)));
        Map<String, Object> view = service.campaignEstimate(3L);
        assertEquals(new BigDecimal("0.9000"), view.get("rate"), "the November card, not today's");
    }

    @Test
    void theTemplatesOwnLanguageDecidesItsCategory() {
        campaign("order_update", "hi", null);
        assertEquals("MARKETING", service.campaignEstimate(3L).get("category"));
        campaign("order_update", "en", null);
        assertEquals("UTILITY", service.campaignEstimate(3L).get("category"));
    }

    @Test
    void aCampaignWithoutATemplateIsPricedAsReplies() {
        campaign(null, null, null);
        Map<String, Object> view = service.campaignEstimate(3L);
        assertEquals("SERVICE", view.get("category"));
        assertEquals(new BigDecimal("0.1150"), view.get("rate"));
        assertEquals(new BigDecimal("113.85"), view.get("amount"));
    }

    @Test
    void anUnknownTemplateGivesNoPriceRatherThanAWrongOne() {
        campaign("vanished", "en", null);
        Map<String, Object> view = service.campaignEstimate(3L);
        assertNull(view.get("category"));
        assertNull(view.get("rate"));
        assertNull(view.get("amount"));
    }

    @Test
    void anotherWorkspacesCampaignIsNotFound() {
        WhatsAppCampaign theirs = campaign("promo", "en", null);
        theirs.setOwnerUserId("someone-else");
        assertThrows(ResourceNotFoundException.class, () -> service.campaignEstimate(3L));
    }
}
