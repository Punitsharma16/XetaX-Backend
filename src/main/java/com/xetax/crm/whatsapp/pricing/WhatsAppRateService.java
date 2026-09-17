package com.xetax.crm.whatsapp.pricing;

import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Meta's India rate card, kept in our own table so estimates are ours to make.
 *
 * <p>Rates change every quarter; the XetaX team adds a dated row from the
 * platform console and every estimate picks the right price for each day.
 * Only marketing, utility and authentication have rows — a reply (service
 * message) is charged at the utility rate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppRateService {

    public static final String MARKET = "IN";

    private final WhatsAppRateRepository repository;

    /**
     * First boot only: the India card as known on 17 September 2026. Utility
     * is ₹0.115 (what WhatsApp Manager bills); marketing and authentication are
     * Meta's published India rates — check them against the rate card in
     * WhatsApp Manager and correct them from the console.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedDefaults() {
        if (repository.count() > 0) return;
        LocalDate from = LocalDate.of(2025, 7, 1);
        repository.saveAll(List.of(
                row(ChargeCategory.MARKETING, "0.8631", from, "Meta India rate card — verify in WhatsApp Manager"),
                row(ChargeCategory.UTILITY, "0.1150", from, "Meta India rate card — also the rate for replies"),
                row(ChargeCategory.AUTHENTICATION, "0.1150", from, "Meta India rate card — verify in WhatsApp Manager")));
        log.info("Seeded the WhatsApp India rate card");
    }

    private static WhatsAppRate row(ChargeCategory category, String rate, LocalDate from, String note) {
        return WhatsAppRate.builder().market(MARKET).category(category)
                .rate(new BigDecimal(rate)).effectiveFrom(from).note(note).build();
    }

    public RateBook book() {
        return RateBook.of(repository.findByMarketOrderByCategoryAscEffectiveFromAsc(MARKET));
    }

    /** Today's price per category and the next scheduled change — for the panel's notes. */
    public Map<String, Object> summary(LocalDate today) {
        RateBook book = book();
        Map<String, Object> categories = new LinkedHashMap<>();
        for (ChargeCategory category : ChargeCategory.values()) {
            ChargeCategory priced = category.pricedAs();   // a reply reads the utility row
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rate", book.rate(priced, today));
            book.next(priced, today).ifPresent(next -> entry.put("next",
                    Map.of("rate", next.getValue(), "from", next.getKey().toString())));
            categories.put(category.name(), entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", MARKET);
        out.put("currency", "INR");
        out.put("asOf", today.toString());
        out.put("categories", categories);
        return out;
    }

    /* ------------------------------------------------ platform console */

    public List<Map<String, Object>> list() {
        return repository.findByMarketOrderByCategoryAscEffectiveFromAsc(MARKET).stream()
                .map(WhatsAppRateService::view).toList();
    }

    public record RateInput(String category, BigDecimal rate, String effectiveFrom, String note) {}

    /** Adds a dated price, or replaces the one already on that date. */
    @Transactional
    public Map<String, Object> upsert(RateInput input) {
        if (input == null) throw new BadRequestException("Nothing to save");
        ChargeCategory category;
        try {
            category = ChargeCategory.valueOf(String.valueOf(input.category()).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown category '" + input.category() + "'");
        }
        if (!category.hasOwnRate()) {
            throw new BadRequestException("Replies are charged at the utility rate — change UTILITY instead");
        }
        if (input.rate() == null || input.rate().signum() < 0) {
            throw new BadRequestException("The rate must be zero or more");
        }
        if (input.rate().compareTo(new BigDecimal("100")) > 0) {
            throw new BadRequestException("That rate is far above any WhatsApp price — check the decimal point");
        }
        LocalDate from;
        try {
            from = LocalDate.parse(String.valueOf(input.effectiveFrom()).trim());
        } catch (Exception e) {
            throw new BadRequestException("Effective date must be YYYY-MM-DD");
        }
        WhatsAppRate row = repository.findByMarketAndCategoryAndEffectiveFrom(MARKET, category, from)
                .orElseGet(() -> WhatsAppRate.builder().market(MARKET).category(category).effectiveFrom(from).build());
        row.setRate(input.rate().setScale(4, RoundingMode.HALF_UP));
        String note = input.note() == null ? null : input.note().trim();
        row.setNote(note == null || note.isEmpty() ? null : note.length() > 255 ? note.substring(0, 255) : note);
        return view(repository.save(row));
    }

    @Transactional
    public void delete(Long id) {
        WhatsAppRate row = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Rate not found"));
        // A category with no price at all would leave every estimate "unpriced".
        long sameCategory = repository.findByMarketOrderByCategoryAscEffectiveFromAsc(MARKET).stream()
                .filter(r -> r.getCategory() == row.getCategory()).count();
        if (sameCategory <= 1) {
            throw new BadRequestException("This is the only " + row.getCategory()
                    + " price — change it instead of deleting it");
        }
        repository.delete(row);
    }

    private static Map<String, Object> view(WhatsAppRate row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("category", row.getCategory().name());
        view.put("rate", row.getRate());
        view.put("effectiveFrom", row.getEffectiveFrom().toString());
        view.put("note", row.getNote());
        return view;
    }
}
