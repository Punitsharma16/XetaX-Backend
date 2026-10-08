package com.xetax.crm.booking.config;

import com.xetax.crm.booking.repository.BookingSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brings slots written before capacity existed in line with it.
 *
 * <p>Two things can be wrong on such a row: capacity arrives as 0 if the
 * column was added without a default, which would make the slot unbookable
 * for ever; and a slot already BOOKED has a bookedCount of 0, which would
 * read as free. Both are fixed once, on the way up, and the statements are
 * no-ops on every start after that.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingSlotBackfill {

    private final BookingSlotRepository slots;

    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    public void fixLegacySlots() {
        int capacities = slots.backfillMissingCapacity();
        int counts = slots.backfillBookedCount();
        if (capacities > 0 || counts > 0) {
            log.info("Booking slots brought up to date: {} given a capacity, {} given a booked count",
                    capacities, counts);
        }
    }
}
