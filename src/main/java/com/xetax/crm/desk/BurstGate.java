package com.xetax.crm.desk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Lets only the last message of a burst be answered.
 *
 * <p>People write the way they speak — "hi", then "I need a quote", then "by
 * tomorrow". Each line opens a turn; this hands every turn a token and keeps
 * only the newest one per session, so when the turns wake up after their wait,
 * the ones that were overtaken stand down and exactly one answers.
 *
 * <p>Deciding by token rather than by "is there a newer message in the table"
 * keeps it exact under concurrency: {@link #shouldAnswer} is a single atomic
 * operation, so two turns waking at the same instant cannot both pass.
 */
@Component
public class BurstGate {

    private final AtomicLong sequence = new AtomicLong();
    private final Map<Long, Long> newest = new ConcurrentHashMap<>();

    /** Opens a turn for this session, standing down any turn still waiting. */
    public long begin(Long sessionId) {
        long token = sequence.incrementAndGet();
        newest.put(sessionId, token);
        return token;
    }

    /**
     * Whether this turn is the one that should answer.
     *
     * <p>True exactly once per token: the winner takes its session's entry
     * with it, so a repeat of the same token — a retry, a double schedule —
     * answers nothing.
     */
    public boolean shouldAnswer(Long sessionId, long token) {
        return newest.remove(sessionId, token);
    }

    /** Forgets a session, for when its chat ends. */
    public void forget(Long sessionId) {
        newest.remove(sessionId);
    }

    /** How many turns are waiting — for tests and for a health check. */
    public int waiting() {
        return newest.size();
    }
}
