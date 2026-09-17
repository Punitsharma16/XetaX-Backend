package com.xetax.crm.whatsapp.pricing;

import com.xetax.crm.whatsapp.pricing.ChargeEstimator.Inbound;
import com.xetax.crm.whatsapp.pricing.ChargeEstimator.Outbound;
import com.xetax.crm.whatsapp.pricing.ChargeEstimator.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The estimate is only worth showing if it lands where Meta's bill lands, so
 * every rule is pinned to the second, in the zone Meta bills in.
 */
class ChargeEstimatorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final String INDIA = "919034908543";

    /** The seeded card: marketing 0.8631, utility 0.1150, authentication 0.1150. */
    private static RateBook card(WhatsAppRate... extra) {
        List<WhatsAppRate> rows = new ArrayList<>(List.of(
                rate(ChargeCategory.MARKETING, "0.8631", "2025-07-01"),
                rate(ChargeCategory.UTILITY, "0.1150", "2025-07-01"),
                rate(ChargeCategory.AUTHENTICATION, "0.1150", "2025-07-01")));
        rows.addAll(List.of(extra));
        return RateBook.of(rows);
    }

    private static WhatsAppRate rate(ChargeCategory category, String value, String from) {
        return WhatsAppRate.builder().market("IN").category(category)
                .rate(new BigDecimal(value)).effectiveFrom(LocalDate.parse(from)).build();
    }

    /** IST wall-clock time → instant, so the tests read like the dashboard. */
    private static Instant ist(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(IST).toInstant();
    }

    private static long ids = 1;

    private static Outbound template(String name, String at) {
        return new Outbound(ids++, 1L, "TEMPLATE", name, "en", "DELIVERED", INDIA, ist(at), ist(at));
    }

    private static Outbound reply(String type, String at) {
        return new Outbound(ids++, 1L, type, null, null, "DELIVERED", INDIA, ist(at), ist(at));
    }

    private static final Map<String, String> CATEGORIES = Map.of(
            "promo", "MARKETING", "order_update", "UTILITY", "otp", "AUTHENTICATION");

    private static Result run(List<Outbound> out, List<Inbound> in, RateBook book, int allowance) {
        return ChargeEstimator.estimate(out, in, o -> CATEGORIES.get(o.templateName()), book, IST, OCT, allowance);
    }

    private static Result run(Outbound... out) {
        return run(List.of(out), List.of(), card(), 0);
    }

    private static ChargeEstimator.Line line(Result result, ChargeCategory category) {
        return result.lines().stream().filter(l -> l.category() == category).findFirst().orElseThrow();
    }

    /* --------------------------------------------------------- categories */

    @Test
    void eachTemplateIsChargedByItsCategory() {
        Result r = run(template("promo", "2026-10-02T10:00"),
                template("order_update", "2026-10-02T10:00"),
                template("otp", "2026-10-02T10:00"));
        assertEquals(new BigDecimal("0.86"), line(r, ChargeCategory.MARKETING).amount());
        assertEquals(new BigDecimal("0.12"), line(r, ChargeCategory.UTILITY).amount());
        assertEquals(new BigDecimal("0.12"), line(r, ChargeCategory.AUTHENTICATION).amount());
        // 0.8631 + 0.1150 + 0.1150 = 1.0931 → 1.09
        assertEquals(new BigDecimal("1.09"), r.total());
        assertEquals(3, r.chargedMessages());
    }

    @Test
    void twoUtilityMessagesMatchWhatWhatsAppManagerShowed() {
        // The screenshot: Utility ₹0.23, total ₹0.23.
        Result r = run(template("order_update", "2026-10-03T09:00"), template("order_update", "2026-10-03T09:05"));
        assertEquals(new BigDecimal("0.23"), line(r, ChargeCategory.UTILITY).amount());
        assertEquals(new BigDecimal("0.23"), r.total());
    }

    @Test
    void everyNonTemplateMessageIsAServiceMessageAtTheUtilityRate() {
        Result r = run(reply("TEXT", "2026-10-04T11:00"), reply("IMAGE", "2026-10-04T11:01"),
                reply("DOCUMENT", "2026-10-04T11:02"), reply("INTERACTIVE", "2026-10-04T11:03"));
        assertEquals(4, line(r, ChargeCategory.SERVICE).messages());
        assertEquals(new BigDecimal("0.46"), line(r, ChargeCategory.SERVICE).amount());
    }

    @Test
    void aUtilityTemplateRightAfterTheCustomerWroteIsStillCharged() {
        Outbound utility = template("order_update", "2026-10-05T12:01");
        Inbound customer = new Inbound(1L, ist("2026-10-05T12:00"), false);
        Result r = run(List.of(utility), List.of(customer), card(), 0);
        assertEquals(1, line(r, ChargeCategory.UTILITY).messages(), "no 24-hour exception any more");
        assertEquals(new BigDecimal("0.12"), r.total());
    }

    @Test
    void allFourLinesAreAlwaysThereInOrder() {
        Result r = run();
        assertEquals(List.of(ChargeCategory.MARKETING, ChargeCategory.UTILITY,
                        ChargeCategory.AUTHENTICATION, ChargeCategory.SERVICE),
                r.lines().stream().map(ChargeEstimator.Line::category).toList());
        assertEquals(new BigDecimal("0.00"), r.total());
    }

    @Test
    void anUnknownTemplateIsCountedNotGuessed() {
        Result r = run(template("mystery", "2026-10-02T10:00"));
        assertEquals(1, r.unknownCategory());
        assertEquals(new BigDecimal("0.00"), r.total());
    }

    /* ------------------------------------------------------- delivery */

    @Test
    void onlyDeliveredOrReadMessagesAreCharged() {
        Outbound failed = new Outbound(ids++, 1L, "TEMPLATE", "promo", "en", "FAILED", INDIA, ist("2026-10-02T10:00"), null);
        Outbound queued = new Outbound(ids++, 1L, "TEMPLATE", "promo", "en", "QUEUED", INDIA, null, null);
        Outbound read = new Outbound(ids++, 1L, "TEMPLATE", "promo", "en", "READ", INDIA, ist("2026-10-02T10:00"), ist("2026-10-02T10:02"));
        Result r = run(failed, queued, read);
        assertEquals(1, line(r, ChargeCategory.MARKETING).messages());
        assertEquals(0, r.awaitingDelivery());
    }

    @Test
    void sentButNotYetDeliveredIsReportedApart() {
        Outbound sent = new Outbound(ids++, 1L, "TEMPLATE", "promo", "en", "SENT", INDIA, ist("2026-10-02T10:00"), null);
        Outbound lastMonth = new Outbound(ids++, 1L, "TEMPLATE", "promo", "en", "SENT", INDIA, ist("2026-09-20T10:00"), null);
        Result r = run(sent, lastMonth);
        assertEquals(1, r.awaitingDelivery());
        assertEquals(new BigDecimal("0.00"), r.total());
    }

    /* --------------------------------------------------- the month edge */

    @Test
    void theMonthStartsAtMidnightIndiaTime() {
        // 23:59:59 IST on 30 Sept belongs to September; 00:00 IST on 1 Oct to October.
        Outbound september = template("promo", "2026-09-30T23:59:59");
        Outbound october = template("promo", "2026-10-01T00:00:00");
        Result r = run(september, october);
        assertEquals(1, line(r, ChargeCategory.MARKETING).messages());
    }

    @Test
    void aMessageIsChargedInTheMonthItWasDelivered() {
        Outbound sentInSeptDeliveredInOct = new Outbound(ids++, 1L, "TEMPLATE", "promo", "en", "DELIVERED", INDIA,
                ist("2026-09-30T23:50"), ist("2026-10-01T00:10"));
        assertEquals(1, line(run(sentInSeptDeliveredInOct), ChargeCategory.MARKETING).messages());
    }

    /* ------------------------------------------------------- countries */

    @Test
    void onlyIndianNumbersArePriced() {
        Outbound uae = new Outbound(ids++, 1L, "TEMPLATE", "promo", "en", "DELIVERED", "971501234567", ist("2026-10-02T10:00"), ist("2026-10-02T10:00"));
        Outbound us = new Outbound(ids++, 1L, "TEXT", null, null, "DELIVERED", "14155550123", ist("2026-10-02T10:00"), ist("2026-10-02T10:00"));
        Outbound bare = new Outbound(ids++, 1L, "TEXT", null, null, "DELIVERED", "9034908543", ist("2026-10-02T10:00"), ist("2026-10-02T10:00"));
        Result r = run(uae, us, bare);
        assertEquals(3, r.otherCountries());
        assertEquals(new BigDecimal("0.00"), r.total());
    }

    /* ------------------------------------------------------------ rates */

    @Test
    void aPriceChangeMidMonthAppliesFromItsDate() {
        RateBook book = card(rate(ChargeCategory.MARKETING, "0.9000", "2026-10-15"));
        Result r = run(List.of(template("promo", "2026-10-14T23:59"), template("promo", "2026-10-15T00:00")),
                List.of(), book, 0);
        // 0.8631 + 0.9000
        assertEquals(new BigDecimal("1.76"), r.total());
    }

    @Test
    void repliesFollowTheUtilityRateWhenItChanges() {
        RateBook book = card(rate(ChargeCategory.UTILITY, "0.1300", "2026-10-10"));
        Result r = run(List.of(reply("TEXT", "2026-10-09T10:00"), reply("TEXT", "2026-10-10T10:00")),
                List.of(), book, 0);
        // 0.1150 + 0.1300
        assertEquals(new BigDecimal("0.25"), line(r, ChargeCategory.SERVICE).amount());
    }

    @Test
    void aDayBeforeAnyPriceIsCountedAsUnpriced() {
        RateBook book = RateBook.of(List.of(rate(ChargeCategory.MARKETING, "0.8631", "2026-10-10")));
        Result r = run(List.of(template("promo", "2026-10-09T10:00")), List.of(), book, 0);
        assertEquals(1, r.unpriced());
        assertEquals(new BigDecimal("0.00"), r.total());
    }

    /* ------------------------------------------ free entry point (ads) */

    private static Inbound fromAd(String at) {
        return new Inbound(1L, ist(at), true);
    }

    @Test
    void anAdChatIsFreeFor72HoursFromTheFirstReply() {
        List<Outbound> out = List.of(
                reply("TEXT", "2026-10-02T12:00"),              // first reply, 2h after the ad click → opens the window
                template("promo", "2026-10-04T11:59"),          // inside
                reply("TEXT", "2026-10-05T11:59:59"),           // last second inside
                reply("TEXT", "2026-10-05T12:00"));             // window closed → charged
        Result r = run(out, List.of(fromAd("2026-10-02T10:00")), card(), 0);
        assertEquals(3, r.freeEntryPoint());
        assertEquals(1, line(r, ChargeCategory.SERVICE).messages());
        assertEquals(0, line(r, ChargeCategory.MARKETING).messages());
    }

    @Test
    void theWindowRunsFromTheReplyNotFromTheAdClick() {
        List<Outbound> out = List.of(
                reply("TEXT", "2026-10-02T20:00"),      // replied 10h after the click
                reply("TEXT", "2026-10-05T19:00"));     // 81h after the click, 71h after the reply → still free
        Result r = run(out, List.of(fromAd("2026-10-02T10:00")), card(), 0);
        assertEquals(2, r.freeEntryPoint());
    }

    @Test
    void aReplyAfter24HoursOpensNoFreeWindow() {
        List<Outbound> out = List.of(reply("TEXT", "2026-10-03T10:00:01"));
        Result r = run(out, List.of(fromAd("2026-10-02T10:00")), card(), 0);
        assertEquals(0, r.freeEntryPoint());
        assertEquals(1, line(r, ChargeCategory.SERVICE).messages());
    }

    @Test
    void anOrdinaryCustomerMessageOpensNoFreeWindow() {
        List<Outbound> out = List.of(reply("TEXT", "2026-10-02T10:05"));
        Result r = run(out, List.of(new Inbound(1L, ist("2026-10-02T10:00"), false)), card(), 0);
        assertEquals(0, r.freeEntryPoint());
    }

    @Test
    void aWindowOnlyCoversItsOwnChat() {
        Outbound otherChat = new Outbound(ids++, 2L, "TEXT", null, null, "DELIVERED", INDIA,
                ist("2026-10-02T12:00"), ist("2026-10-02T12:00"));
        List<Outbound> out = List.of(reply("TEXT", "2026-10-02T11:00"), otherChat);
        Result r = run(out, List.of(fromAd("2026-10-02T10:00")), card(), 0);
        assertEquals(1, r.freeEntryPoint());
        assertEquals(1, line(r, ChargeCategory.SERVICE).messages());
    }

    @Test
    void aWindowOpenedLastMonthStillCoversTheFirstDaysOfThisOne() {
        List<Outbound> out = List.of(
                new Outbound(ids++, 1L, "TEXT", null, null, "DELIVERED", INDIA, ist("2026-09-30T20:00"), ist("2026-09-30T20:00")),
                reply("TEXT", "2026-10-02T10:00"));
        Result r = run(out, List.of(fromAd("2026-09-30T19:00")), card(), 0);
        assertEquals(1, r.freeEntryPoint(), "the October message; the September one is not this month's");
        assertEquals(new BigDecimal("0.00"), r.total());
    }

    /* -------------------------------------------------------- allowance */

    @Test
    void anAllowanceCoversTheEarliestRepliesOnly() {
        List<Outbound> out = List.of(
                reply("TEXT", "2026-10-03T10:00"),
                reply("TEXT", "2026-10-01T10:00"),
                reply("TEXT", "2026-10-02T10:00"),
                template("order_update", "2026-10-01T09:00"));
        Result r = run(out, List.of(), card(), 2);
        assertEquals(2, r.freeAllowance());
        assertEquals(1, line(r, ChargeCategory.SERVICE).messages());
        assertEquals(1, line(r, ChargeCategory.UTILITY).messages(), "templates never use the allowance");
    }

    @Test
    void noAllowanceByDefault() {
        Result r = run(List.of(reply("TEXT", "2026-10-03T10:00")), List.of(), card(), 0);
        assertEquals(0, r.freeAllowance());
        assertEquals(1, line(r, ChargeCategory.SERVICE).messages());
    }

    /* ---------------------------------------------------------- helpers */

    @Test
    void indiaIsTwelveDigitsStartingWith91() {
        assertTrue(ChargeEstimator.isIndia("919034908543"));
        assertTrue(ChargeEstimator.isIndia("+91 90349 08543"));
        assertFalse(ChargeEstimator.isIndia("9034908543"));
        assertFalse(ChargeEstimator.isIndia("9190349085431"));
        assertFalse(ChargeEstimator.isIndia(null));
    }

    @Test
    void templateCategoriesAreReadLoosely() {
        assertEquals(ChargeCategory.MARKETING, ChargeEstimator.templateLine(" marketing "));
        assertNull(ChargeEstimator.templateLine("SERVICE"), "a template is never a service message");
        assertNull(ChargeEstimator.templateLine(null));
    }
}
