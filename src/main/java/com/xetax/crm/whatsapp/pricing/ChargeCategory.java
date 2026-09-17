package com.xetax.crm.whatsapp.pricing;

/**
 * What Meta charges an outbound message as, under the pricing in force from
 * 1 October 2026.
 *
 * <p>MARKETING, UTILITY and AUTHENTICATION each have a price in the rate
 * table. SERVICE — any non-template message — has no price of its own: Meta
 * charges it at the utility rate, so it always reads the UTILITY row and the
 * two can never drift apart.
 */
public enum ChargeCategory {
    MARKETING,
    UTILITY,
    AUTHENTICATION,
    SERVICE;

    /** The rate-table row this category is priced from. */
    public ChargeCategory pricedAs() {
        return this == SERVICE ? UTILITY : this;
    }

    /** Only these have rows in the rate table. */
    public boolean hasOwnRate() {
        return this != SERVICE;
    }
}
