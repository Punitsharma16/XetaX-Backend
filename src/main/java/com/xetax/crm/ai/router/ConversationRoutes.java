package com.xetax.crm.ai.router;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The domains a conversation was last routed to.
 *
 * <p>Panel chat is a conversation, so half the messages carry no subject of
 * their own: "haan kar do", "usme ek aur add karo", "theek hai". Routing those
 * on their own words would send the assistant a tool set that has nothing to
 * do with what is being discussed. Remembering the previous turn's domains and
 * carrying them forward is what makes narrow routing safe for follow-ups.
 *
 * <p>Deliberately in-process and not in Redis. A miss here costs nothing —
 * {@link ToolRouter} falls back to the full tool set, which is what every
 * request did before the router existed — so a second instance or a restart
 * degrades to the old behaviour instead of breaking. Adding a Redis round trip
 * to a latency-sensitive path to avoid that is not worth it.
 */
class ConversationRoutes {

    /** A conversation is stale long before the chat memory's 1-day TTL. */
    private static final long TTL_MILLIS = 30 * 60 * 1000L;

    /** Bound on idle conversations kept; a busy panel never approaches it. */
    private static final int MAX_ENTRIES = 5_000;

    private record Entry(EnumSet<ToolDomain> domains, long touchedAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** Domains last used on this conversation, or empty if none / expired. */
    Set<ToolDomain> recall(String conversationId, long now) {
        if (conversationId == null) {
            return EnumSet.noneOf(ToolDomain.class);
        }
        Entry entry = this.entries.get(conversationId);
        if (entry == null) {
            return EnumSet.noneOf(ToolDomain.class);
        }
        if (now - entry.touchedAt() > TTL_MILLIS) {
            this.entries.remove(conversationId, entry);
            return EnumSet.noneOf(ToolDomain.class);
        }
        return EnumSet.copyOf(entry.domains());
    }

    /** Record the domains this conversation is on now. */
    void remember(String conversationId, Set<ToolDomain> domains, long now) {
        if (conversationId == null || domains.isEmpty()) {
            return;
        }
        this.entries.put(conversationId, new Entry(EnumSet.copyOf(domains), now));
        if (this.entries.size() > MAX_ENTRIES) {
            evictExpired(now);
        }
    }

    private void evictExpired(long now) {
        Iterator<Map.Entry<String, Entry>> it = this.entries.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().touchedAt() > TTL_MILLIS) {
                it.remove();
            }
        }
        if (this.entries.size() > MAX_ENTRIES) {
            // Still over after dropping the expired ones: this is a bound, not
            // a cache we need to be clever about, and losing an entry only
            // costs that conversation one full-tool-set request.
            this.entries.clear();
        }
    }

    /** Test seam. */
    int size() {
        return this.entries.size();
    }
}
