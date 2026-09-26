package com.xetax.crm.ai.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Picks the tools and the prompt one message actually needs.
 *
 * <p>Before this, every message carried all 49 tool schemas and the whole
 * system prompt — about 6,700 tokens spent before the question was read. On
 * the free Groq tier's 8,000 tokens/minute that left room for roughly one
 * request per minute, which is what the panel's production 413s were.
 *
 * <p>The routing is deterministic on purpose. The alternative, asking the
 * model to classify first, buys a little accuracy for a whole extra round
 * trip on a path where users already complain about latency. Matching words
 * costs microseconds, and the design makes a miss cheap rather than trying to
 * make misses impossible:
 *
 * <ol>
 * <li>No domain matched and nothing is remembered for this conversation? Send
 * everything — byte-for-byte the request the panel sent before the router
 * existed.</li>
 * <li>A domain matched? Send it together with the previous turn's domains, so
 * "un leads ko whatsapp bhejo" keeps the records tools that "leads" alone
 * brought in last turn.</li>
 * <li>Name-to-id resolution tools ride along with every request
 * ({@link ToolDomain#ALWAYS_AVAILABLE}), because that is the step a narrowed
 * tool set is most likely to strand.</li>
 * </ol>
 *
 * <p>Measured against the 5,555 tokens the 49 tool schemas cost on Groq and
 * the 1,139 the full prompt costs, the fixed overhead per request drops from
 * 6,694 tokens to:
 *
 * <pre>
 *   CONTACTS  1,369      MEETINGS  1,712      TEAM         2,073
 *   AGENTS    1,664      WHATSAPP  1,895      AUTOMATIONS  2,379
 *   RECORDS   2,110      FORMS     2,938      RECORDS+WA   2,802
 * </pre>
 *
 * <p>On a corpus of real panel questions 84% route; the rest are the generic
 * ones ("XetaX kya hai", "hello") that are answered from retrieved knowledge
 * anyway and correctly get everything. See {@code RouterCoverageTest}.
 *
 * <p>{@code xetax.ai.tool-router.enabled=false} turns all of it off and
 * restores the old single-tool-set behaviour without a redeploy.
 */
@Component
public class ToolRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolRouter.class);

    /** Split on anything that is not a letter or a digit, Devanagari included. */
    private static final Pattern WORDS = Pattern.compile("[^\\p{L}\\p{N}]+");

    /**
     * Shortest keyword allowed to match across removed spaces, so "what s app"
     * and "whats app" still reach WhatsApp. Short words like "wa" or "form"
     * would fire inside unrelated words, so they stay token-only.
     */
    private static final int COMPACT_MATCH_MIN_LENGTH = 6;

    private static final Set<ToolDomain> ALL_DOMAINS =
            EnumSet.allOf(ToolDomain.class);

    private final ToolRegistry registry;
    private final ConversationRoutes routes = new ConversationRoutes();
    private final boolean enabled;

    public ToolRouter(ToolRegistry registry,
                      @Value("${xetax.ai.tool-router.enabled:true}") boolean enabled) {
        this.registry = registry;
        this.enabled = enabled;
        log.info("AI tool router {} ({} tools registered)",
                enabled ? "enabled" : "DISABLED — every request carries all tools",
                registry.names().size());
    }

    /**
     * Route one message.
     *
     * @param conversationId the chat this message belongs to; null is fine and
     *                       simply means no follow-up context
     * @param message        what the user typed
     */
    public RoutingDecision route(String conversationId, String message) {
        if (!this.enabled) {
            return fullSet();
        }

        Set<ToolDomain> matched = match(message);
        long now = System.currentTimeMillis();
        Set<ToolDomain> previous = this.routes.recall(conversationId, now);

        if (matched.isEmpty()) {
            Set<ToolDomain> remembered = previous;
            if (remembered.isEmpty()) {
                // Nothing in the words, nothing in the conversation: this is
                // the "what can you do?" case. Behave exactly as before.
                log.debug("Tool router: no signal, sending all {} tools",
                        this.registry.names().size());
                return fullSet();
            }
            // A follow-up like "haan kar do" — stay where the conversation is.
            this.routes.remember(conversationId, remembered, now);
            return decide(remembered);
        }

        /*
         * Remember only what THIS message said, but answer it with the
         * previous turn's domains as well. Storing the union instead would
         * make the memory grow and never shrink: records, then WhatsApp, then
         * team, and by the fifth turn the conversation is back to carrying
         * every tool. One turn of context is what a follow-up actually needs.
         */
        this.routes.remember(conversationId, matched, now);
        Set<ToolDomain> domains = EnumSet.copyOf(matched);
        domains.addAll(previous);
        return decide(domains);
    }

    private RoutingDecision decide(Set<ToolDomain> domains) {
        RoutingDecision decision = new RoutingDecision(
                Set.copyOf(domains),
                this.registry.forDomains(domains),
                AssistantPrompt.forDomains(domains),
                true);
        log.debug("Tool router: {} -> {} of {} tools",
                domains, decision.tools().size(), this.registry.names().size());
        return decision;
    }

    private RoutingDecision fullSet() {
        return new RoutingDecision(ALL_DOMAINS, this.registry.all(),
                AssistantPrompt.forDomains(ALL_DOMAINS), false);
    }

    /**
     * Domains whose keywords appear in the message.
     *
     * <p>Every domain that matches is kept, not just the best one — "leads
     * nikalo aur Ravi ko whatsapp karo" is two jobs and needs both tool sets.
     */
    static Set<ToolDomain> match(String message) {
        Set<ToolDomain> matched = EnumSet.noneOf(ToolDomain.class);
        if (message == null || message.isBlank()) {
            return matched;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        // A HashSet, not Set.of: a repeated word would make Set.of throw.
        Set<String> words = new HashSet<>(Arrays.asList(WORDS.split(lower)));
        String compact = WORDS.matcher(lower).replaceAll("");

        for (ToolDomain domain : ToolDomain.values()) {
            for (String keyword : domain.keywords()) {
                if (words.contains(keyword)
                        || (keyword.length() >= COMPACT_MATCH_MIN_LENGTH
                            && compact.contains(keyword))) {
                    matched.add(domain);
                    break;
                }
            }
        }
        return matched;
    }
}
