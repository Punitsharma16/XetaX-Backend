package com.xetax.crm.whatsapp.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** An in-memory copy of the rate table, answering "what did this cost on that day". */
public final class RateBook {

    private final Map<ChargeCategory, TreeMap<LocalDate, BigDecimal>> rates = new EnumMap<>(ChargeCategory.class);

    public static RateBook of(Collection<WhatsAppRate> rows) {
        RateBook book = new RateBook();
        for (WhatsAppRate row : rows) {
            if (row.getCategory() == null || row.getEffectiveFrom() == null || row.getRate() == null) continue;
            book.rates.computeIfAbsent(row.getCategory(), c -> new TreeMap<>())
                    .put(row.getEffectiveFrom(), row.getRate());
        }
        return book;
    }

    /** The price on that day, or null when the table has nothing that early. */
    public BigDecimal rate(ChargeCategory category, LocalDate day) {
        TreeMap<LocalDate, BigDecimal> byDate = rates.get(category);
        if (byDate == null || day == null) return null;
        Map.Entry<LocalDate, BigDecimal> entry = byDate.floorEntry(day);
        return entry == null ? null : entry.getValue();
    }

    /** The next scheduled change after that day, if any. */
    public Optional<Map.Entry<LocalDate, BigDecimal>> next(ChargeCategory category, LocalDate day) {
        TreeMap<LocalDate, BigDecimal> byDate = rates.get(category);
        if (byDate == null || day == null) return Optional.empty();
        return Optional.ofNullable(byDate.higherEntry(day));
    }
}
