package com.xetax.crm.billing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The plans and top-up packs in one place. Quotas are messages per calendar
 * month; the top-up balance is shared by the assistant and the public agents
 * and never expires.
 */
public final class PlanCatalog {

    private PlanCatalog() {}

    public record Plan(String key, String label, int assistantMonthly, int agentMonthly,
                       boolean trial, int trialDays,
                       int maxMembers, int maxForms, int maxRecords) {}

    public record Pack(String key, String label, int messages, int amountPaise) {}

    public static final Map<String, Plan> PLANS = new LinkedHashMap<>() {{
        put("TRIAL",    new Plan("TRIAL",    "Free Trial",  200,  100, true, 14,   5, 999,  25_000));
        put("STARTER",  new Plan("STARTER",  "Starter",      25,    0, false, 0,   1,   2,     500));
        put("GROWTH",   new Plan("GROWTH",   "Growth",      500,    0, false, 0,   5, 999,  25_000));
        put("BUSINESS", new Plan("BUSINESS", "Business",   2000, 3000, false, 0, 999, 999, 999_999));
    }};

    public static final List<Pack> PACKS = List.of(
            new Pack("PACK_1K",  "1,000 AI messages",  1_000,  19_900),
            new Pack("PACK_5K",  "5,000 AI messages",  5_000,  89_900),
            new Pack("PACK_10K", "10,000 AI messages", 10_000, 159_900));

    public static Plan plan(String key) {
        return PLANS.getOrDefault(key, PLANS.get("STARTER"));
    }

    public static Pack pack(String key) {
        return PACKS.stream().filter(p -> p.key().equals(key)).findFirst().orElse(null);
    }
}
