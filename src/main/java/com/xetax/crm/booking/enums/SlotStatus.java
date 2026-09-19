package com.xetax.crm.booking.enums;

/** Where a slot stands. Only OPEN slots are ever shown to a customer. */
public enum SlotStatus {
    /** Free — offered on the public page, to the bot and in the panel. */
    OPEN,
    /** Taken by a customer; carries the record the booking became. */
    BOOKED,
    /** Kept back by the salon (leave, break) — never offered, never booked. */
    BLOCKED
}
