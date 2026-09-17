package com.xetax.crm.whatsapp.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;

/**
 * What Meta will charge for a month of outbound messages, under the pricing in
 * force from 1 October 2026.
 *
 * <p>The rules, each from Meta's pricing documentation:
 * <ul>
 *   <li>Only a <b>delivered</b> message is charged, on the day it was
 *       delivered. Failed messages cost nothing; sent-but-not-yet-delivered
 *       ones are reported apart, since they may still be charged.</li>
 *   <li>The <b>recipient's calling code</b> decides the rate. Only India (+91)
 *       is priced here; other countries are counted, never guessed.</li>
 *   <li>A template is charged by its <b>category</b> — marketing, utility or
 *       authentication — wherever and whenever it is sent.</li>
 *   <li>Any non-template message is a <b>service</b> message, charged at the
 *       utility rate.</li>
 *   <li>A customer arriving from a <b>click-to-WhatsApp ad or Page button</b>
 *       who gets a reply within 24 hours opens a free entry point window at
 *       that reply: everything sent in the next 72 hours is free.</li>
 * </ul>
 * Pure: no database, no clock — every input is passed in, so each rule is
 * testable to the second.
 */
public final class ChargeEstimator {

    static final Duration FREE_ENTRY_REPLY_WITHIN = Duration.ofHours(24);
    static final Duration FREE_ENTRY_WINDOW = Duration.ofHours(72);

    /** Shown in this order, always — a month with no marketing still shows ₹0 for it. */
    public static final List<ChargeCategory> LINES = List.of(
            ChargeCategory.MARKETING, ChargeCategory.UTILITY,
            ChargeCategory.AUTHENTICATION, ChargeCategory.SERVICE);

    private ChargeEstimator() {}

    /**
     * One message we sent. sendTime is when it left us (the moment the free
     * entry point window is judged at); chargeTime is when it was delivered,
     * or read if no delivery receipt arrived; null while undelivered.
     */
    public record Outbound(long id, Long conversationId, String messageType, String templateName,
                           String templateLanguage, String status, String toPhone,
                           Instant sendTime, Instant chargeTime) {}

    /** One message a customer sent us; freeEntryPoint when it came from an ad or a Page button. */
    public record Inbound(Long conversationId, Instant time, boolean freeEntryPoint) {}

    public record Line(ChargeCategory category, long messages, BigDecimal amount) {}

    public record Result(BigDecimal total, List<Line> lines, long chargedMessages,
                         long freeEntryPoint, long freeAllowance, long awaitingDelivery,
                         long otherCountries, long unknownCategory, long unpriced) {}

    /**
     * @param templateCategory the template's Meta category (MARKETING, UTILITY,
     *                         AUTHENTICATION) for a template message, or null when unknown
     * @param freeServicePerMonth service messages Meta does not charge each month (0 if none)
     */
    public static Result estimate(List<Outbound> outbound, List<Inbound> inbound,
                                  Function<Outbound, String> templateCategory,
                                  RateBook rates, ZoneId zone, YearMonth month,
                                  int freeServicePerMonth) {
        Map<Long, List<Instant>> entryTimes = new HashMap<>();
        for (Inbound in : inbound) {
            if (in.freeEntryPoint() && in.conversationId() != null && in.time() != null) {
                entryTimes.computeIfAbsent(in.conversationId(), k -> new ArrayList<>()).add(in.time());
            }
        }
        Map<Long, List<Instant>> sendTimes = new HashMap<>();
        for (Outbound out : outbound) {
            if (out.conversationId() != null && out.sendTime() != null) {
                sendTimes.computeIfAbsent(out.conversationId(), k -> new ArrayList<>()).add(out.sendTime());
            }
        }
        sendTimes.values().forEach(Collections::sort);
        Map<Long, List<Instant[]>> freeWindows = freeEntryWindows(entryTimes, sendTimes);

        Map<ChargeCategory, Long> counts = new EnumMap<>(ChargeCategory.class);
        Map<ChargeCategory, BigDecimal> amounts = new EnumMap<>(ChargeCategory.class);
        for (ChargeCategory line : LINES) {
            counts.put(line, 0L);
            amounts.put(line, BigDecimal.ZERO);
        }
        long freeEntryPoint = 0, awaiting = 0, otherCountries = 0, unknownCategory = 0, unpriced = 0;
        List<Charge> service = new ArrayList<>();

        for (Outbound out : outbound) {
            String status = out.status() == null ? "" : out.status();

            if ("SENT".equals(status)) {
                if (out.sendTime() != null && inMonth(out.sendTime(), zone, month)) awaiting++;
                continue;
            }
            if (!"DELIVERED".equals(status) && !"READ".equals(status)) continue;
            if (out.chargeTime() == null || !inMonth(out.chargeTime(), zone, month)) continue;

            if (!isIndia(out.toPhone())) {
                otherCountries++;
                continue;
            }
            if (out.sendTime() != null && insideAny(freeWindows.get(out.conversationId()), out.sendTime())) {
                freeEntryPoint++;
                continue;
            }

            ChargeCategory line;
            if ("TEMPLATE".equals(out.messageType())) {
                line = templateLine(templateCategory.apply(out));
                if (line == null) {
                    unknownCategory++;
                    continue;
                }
            } else {
                line = ChargeCategory.SERVICE;
            }

            LocalDate day = out.chargeTime().atZone(zone).toLocalDate();
            BigDecimal rate = rates.rate(line.pricedAs(), day);
            if (rate == null) {
                unpriced++;
                continue;
            }
            if (line == ChargeCategory.SERVICE) {
                service.add(new Charge(out.chargeTime(), rate));
                continue;
            }
            counts.merge(line, 1L, Long::sum);
            amounts.merge(line, rate, BigDecimal::add);
        }

        // A monthly allowance, if Meta gives one, covers the earliest service messages.
        service.sort(Comparator.comparing(Charge::time));
        int freeAllowance = Math.min(Math.max(freeServicePerMonth, 0), service.size());
        for (int i = freeAllowance; i < service.size(); i++) {
            counts.merge(ChargeCategory.SERVICE, 1L, Long::sum);
            amounts.merge(ChargeCategory.SERVICE, service.get(i).rate(), BigDecimal::add);
        }

        List<Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        long charged = 0;
        for (ChargeCategory category : LINES) {
            BigDecimal amount = amounts.get(category);
            total = total.add(amount);
            charged += counts.get(category);
            lines.add(new Line(category, counts.get(category), money(amount)));
        }
        return new Result(money(total), lines, charged, freeEntryPoint, freeAllowance,
                awaiting, otherCountries, unknownCategory, unpriced);
    }

    /** A template's Meta category as a charge line; null when it is not one Meta prices. */
    static ChargeCategory templateLine(String category) {
        if (category == null) return null;
        return switch (category.trim().toUpperCase(Locale.ROOT)) {
            case "MARKETING" -> ChargeCategory.MARKETING;
            case "UTILITY" -> ChargeCategory.UTILITY;
            case "AUTHENTICATION" -> ChargeCategory.AUTHENTICATION;
            default -> null;
        };
    }

    private record Charge(Instant time, BigDecimal rate) {}

    /** A reply within 24 hours of an ad/Page message opens 72 free hours from that reply. */
    static Map<Long, List<Instant[]>> freeEntryWindows(Map<Long, List<Instant>> entryTimes,
                                                        Map<Long, List<Instant>> sendTimes) {
        Map<Long, List<Instant[]>> windows = new HashMap<>();
        entryTimes.forEach((conversationId, entries) -> {
            List<Instant> sends = sendTimes.get(conversationId);
            if (sends == null || sends.isEmpty()) return;
            for (Instant entry : entries) {
                Instant firstReply = ceiling(sends, entry);
                if (firstReply == null || firstReply.isAfter(entry.plus(FREE_ENTRY_REPLY_WITHIN))) continue;
                windows.computeIfAbsent(conversationId, k -> new ArrayList<>())
                        .add(new Instant[]{firstReply, firstReply.plus(FREE_ENTRY_WINDOW)});
            }
        });
        return windows;
    }

    static boolean insideAny(List<Instant[]> windows, Instant time) {
        if (windows == null) return false;
        for (Instant[] window : windows) {
            if (!time.isBefore(window[0]) && time.isBefore(window[1])) return true;
        }
        return false;
    }

    static boolean isIndia(String phone) {
        if (phone == null) return false;
        String digits = phone.replaceAll("\\D", "");
        return digits.length() == 12 && digits.startsWith("91");
    }

    static boolean inMonth(Instant time, ZoneId zone, YearMonth month) {
        return YearMonth.from(time.atZone(zone)).equals(month);
    }

    private static Instant ceiling(List<Instant> sorted, Instant key) {
        int i = Collections.binarySearch(sorted, key);
        if (i >= 0) return sorted.get(i);
        int insert = -i - 1;
        return insert >= sorted.size() ? null : sorted.get(insert);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
