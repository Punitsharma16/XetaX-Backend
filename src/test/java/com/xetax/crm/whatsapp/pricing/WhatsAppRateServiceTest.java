package com.xetax.crm.whatsapp.pricing;

import com.xetax.crm.common.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The rate table: seeded once, edited by date, never left without a price. */
class WhatsAppRateServiceTest {

    private final List<WhatsAppRate> rows = new ArrayList<>();
    private WhatsAppRateRepository repository;
    private WhatsAppRateService service;

    @BeforeEach
    void setUp() {
        repository = mock(WhatsAppRateRepository.class);
        when(repository.count()).thenAnswer(inv -> (long) rows.size());
        when(repository.saveAll(anyIterable())).thenAnswer(inv -> {
            Iterable<WhatsAppRate> saved = inv.getArgument(0);
            saved.forEach(this::store);
            return saved;
        });
        when(repository.save(any())).thenAnswer(inv -> store(inv.getArgument(0)));
        when(repository.findByMarketOrderByCategoryAscEffectiveFromAsc("IN")).thenAnswer(inv -> rows.stream()
                .sorted(Comparator.comparing(WhatsAppRate::getCategory).thenComparing(WhatsAppRate::getEffectiveFrom))
                .toList());
        when(repository.findByMarketAndCategoryAndEffectiveFrom(eq("IN"), any(), any())).thenAnswer(inv ->
                rows.stream().filter(r -> r.getCategory() == inv.getArgument(1)
                        && r.getEffectiveFrom().equals(inv.getArgument(2))).findFirst());
        when(repository.findById(any())).thenAnswer(inv ->
                rows.stream().filter(r -> Objects.equals(r.getId(), inv.getArgument(0))).findFirst());
        doAnswer(inv -> rows.remove((WhatsAppRate) inv.getArgument(0))).when(repository).delete(any());
        service = new WhatsAppRateService(repository);
    }

    private WhatsAppRate store(WhatsAppRate row) {
        if (row.getId() == null) {
            row.setId((long) rows.size() + 1);
            rows.add(row);
        }
        return row;
    }

    @Test
    void theFirstBootSeedsTheIndiaCard() {
        service.seedDefaults();
        RateBook book = service.book();
        LocalDate day = LocalDate.of(2026, 10, 1);
        assertEquals(new BigDecimal("0.8631"), book.rate(ChargeCategory.MARKETING, day));
        assertEquals(new BigDecimal("0.1150"), book.rate(ChargeCategory.UTILITY, day));
        assertEquals(new BigDecimal("0.1150"), book.rate(ChargeCategory.AUTHENTICATION, day));
        assertEquals(3, rows.size(), "replies have no row of their own");
    }

    @Test
    void seedingNeverOverwritesEditedRates() {
        service.seedDefaults();
        service.upsert(new WhatsAppRateService.RateInput("MARKETING", new BigDecimal("0.95"), "2025-07-01", null));
        service.seedDefaults();
        assertEquals(new BigDecimal("0.9500"), service.book().rate(ChargeCategory.MARKETING, LocalDate.of(2026, 10, 1)));
        assertEquals(3, rows.size());
    }

    @Test
    void aReplyIsPricedAtTheUtilityRateIncludingTheNextChange() {
        service.seedDefaults();
        service.upsert(new WhatsAppRateService.RateInput("UTILITY", new BigDecimal("0.13"), "2027-01-01", "Q1 card"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> categories =
                (Map<String, Map<String, Object>>) service.summary(LocalDate.of(2026, 10, 1)).get("categories");
        assertEquals(new BigDecimal("0.1150"), categories.get("SERVICE").get("rate"));
        assertEquals(Map.of("rate", new BigDecimal("0.1300"), "from", "2027-01-01"), categories.get("SERVICE").get("next"));
        assertEquals(categories.get("UTILITY"), categories.get("SERVICE"));
    }

    @Test
    void aPriceOnTheSameDateIsReplacedNotDuplicated() {
        service.seedDefaults();
        service.upsert(new WhatsAppRateService.RateInput("utility", new BigDecimal("0.12"), "2025-07-01", " fixed "));
        assertEquals(3, rows.size());
        WhatsAppRate utility = rows.stream().filter(r -> r.getCategory() == ChargeCategory.UTILITY).findFirst().orElseThrow();
        assertEquals(new BigDecimal("0.1200"), utility.getRate());
        assertEquals("fixed", utility.getNote());
    }

    @Test
    void badInputIsRefusedWithAReason() {
        assertThrows(BadRequestException.class, () -> service.upsert(
                new WhatsAppRateService.RateInput("SERVICE", BigDecimal.ONE, "2026-10-01", null)), "replies follow utility");
        assertThrows(BadRequestException.class, () -> service.upsert(
                new WhatsAppRateService.RateInput("PROMO", BigDecimal.ONE, "2026-10-01", null)));
        assertThrows(BadRequestException.class, () -> service.upsert(
                new WhatsAppRateService.RateInput("UTILITY", new BigDecimal("-0.1"), "2026-10-01", null)));
        assertThrows(BadRequestException.class, () -> service.upsert(
                new WhatsAppRateService.RateInput("UTILITY", new BigDecimal("115"), "2026-10-01", null)), "a slipped decimal point");
        assertThrows(BadRequestException.class, () -> service.upsert(
                new WhatsAppRateService.RateInput("UTILITY", BigDecimal.ONE, "01/10/2026", null)));
        assertThrows(BadRequestException.class, () -> service.upsert(null));
    }

    @Test
    void theLastPriceOfACategoryCannotBeDeleted() {
        service.seedDefaults();
        Long marketing = rows.stream().filter(r -> r.getCategory() == ChargeCategory.MARKETING).findFirst().orElseThrow().getId();
        assertThrows(BadRequestException.class, () -> service.delete(marketing));

        service.upsert(new WhatsAppRateService.RateInput("MARKETING", new BigDecimal("0.9"), "2026-10-15", null));
        service.delete(marketing);
        assertEquals(new BigDecimal("0.9000"), service.book().rate(ChargeCategory.MARKETING, LocalDate.of(2026, 10, 20)));
    }
}
