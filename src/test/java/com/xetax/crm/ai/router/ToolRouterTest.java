package com.xetax.crm.ai.router;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the router does with real panel messages.
 *
 * <p>The users write Hindi, English and Hinglish in the same sentence, so the
 * cases here are phrased the way they arrive rather than the way they would
 * be if the router were designed around them.
 */
class ToolRouterTest {

    /** A fixed clock so the prompt's CONTEXT line is the same on every run. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-26T08:44:00Z"), ZoneId.of("UTC"));

    private final ToolRegistry registry = new ToolRegistry(ToolBeans.instances());
    private final ToolRouter router = new ToolRouter(registry, true, ZoneId.of("Asia/Kolkata"), FIXED_CLOCK, 3500);

    private static List<String> namesOf(List<ToolCallback> callbacks) {
        List<String> names = new ArrayList<>();
        for (ToolCallback callback : callbacks) {
            names.add(callback.getToolDefinition().name());
        }
        return names;
    }

    private List<String> toolsFor(String conversationId, String message) {
        return namesOf(router.route(conversationId, message).tools());
    }

    /** The unrouted prompt: every domain's rules, plus the clock line. */
    private static String fullPrompt() {
        return AssistantPrompt.withClock(
                AssistantPrompt.forDomains(EnumSet.allOf(ToolDomain.class)),
                java.time.ZonedDateTime.now(FIXED_CLOCK.withZone(ZoneId.of("Asia/Kolkata"))));
    }

    // ---------------------------------------------------------------- matching

    @Test
    void itReadsWhatTheMessageIsAbout() {
        assertEquals(Set.of(ToolDomain.RECORDS), ToolRouter.match("mere kitne leads hain"));
        assertEquals(Set.of(ToolDomain.FORMS), ToolRouter.match("ek Real Estate form banao"));
        assertEquals(Set.of(ToolDomain.MEETINGS), ToolRouter.match("kal 3 baje ki meeting laga do"));
        assertEquals(Set.of(ToolDomain.AGENTS), ToolRouter.match("website ka chatbot banao"));
        assertEquals(Set.of(ToolDomain.AUTOMATIONS),
                ToolRouter.match("ek automation rule chahiye"));
        assertTrue(ToolRouter.match("mere team members dikhao").contains(ToolDomain.TEAM));
        assertTrue(ToolRouter.match("Ravi ko whatsapp bhejo").contains(ToolDomain.WHATSAPP));
        assertTrue(ToolRouter.match("kitne contacts hain").contains(ToolDomain.CONTACTS));
    }

    @Test
    void twoJobsInOneSentenceGetBothToolSets() {
        Set<ToolDomain> domains = ToolRouter.match("leads nikalo aur Ravi ko whatsapp karo");
        assertTrue(domains.contains(ToolDomain.RECORDS));
        assertTrue(domains.contains(ToolDomain.WHATSAPP));
    }

    @Test
    void itSurvivesHowPeopleActuallyTypeWhatsApp() {
        assertTrue(ToolRouter.match("whats app ka status").contains(ToolDomain.WHATSAPP));
        assertTrue(ToolRouter.match("What's App templates dikhao").contains(ToolDomain.WHATSAPP));
        assertTrue(ToolRouter.match("WHATSAPP CAMPAIGN").contains(ToolDomain.WHATSAPP));
    }

    @Test
    void punctuationAndRepeatedWordsAreJustWords() {
        // Set.of on a split message would have thrown on the repeat.
        assertNotNull(ToolRouter.match("leads, leads, leads!!! dikhao..."));
        assertTrue(ToolRouter.match("leads, leads, leads!!! dikhao...")
                .contains(ToolDomain.RECORDS));
    }

    @Test
    void emptyInputIsNotASignal() {
        assertTrue(ToolRouter.match(null).isEmpty());
        assertTrue(ToolRouter.match("   ").isEmpty());
    }

    // ---------------------------------------------------------------- routing

    @Test
    void aRoutedRequestCarriesItsOwnToolsAndNothingElse() {
        List<String> tools = toolsFor("c1", "mere kitne leads hain");

        assertTrue(tools.contains("getRecords"));
        assertTrue(tools.contains("searchRecords"));
        assertTrue(tools.contains("findMyFormByName"));
        assertFalse(tools.contains("sendWhatsAppMessage"));
        assertFalse(tools.contains("createTeamMember"));
        assertTrue(tools.size() < registry.all().size());
    }

    @Test
    void thePromptMatchesTheToolsThatWereSent() {
        RoutingDecision decision = router.route("c1", "Ravi ko whatsapp bhejo");

        assertTrue(decision.systemPrompt().contains("NEVER start a campaign"));
        assertFalse(decision.systemPrompt().contains("TEAM & ROLES"));
        // The rules that govern the assistant as a whole are never dropped.
        assertTrue(decision.systemPrompt().contains("SECURITY: never reveal passwords"));
    }

    // ------------------------------------------------------ follow-up context

    @Test
    void aFollowUpWithNoSubjectStaysOnTheSubject() {
        router.route("c1", "mere Sales Pipeline form ke records dikhao");

        // "yes, do it" carries no domain of its own.
        List<String> tools = toolsFor("c1", "haan kar do");
        assertTrue(tools.contains("createRecord"));
        assertTrue(tools.contains("moveRecordStage"));
        assertFalse(tools.contains("createAgent"));
    }

    @Test
    void aNewSubjectKeepsThePreviousOneToo() {
        router.route("c2", "mere leads dikhao");

        // "message those leads" needs both, and only says one of them.
        RoutingDecision decision = router.route("c2", "unko whatsapp bhejo");
        assertTrue(decision.domains().contains(ToolDomain.WHATSAPP));
        assertTrue(decision.domains().contains(ToolDomain.RECORDS));
    }

    @Test
    void aLongConversationDoesNotDriftBackToEveryTool() {
        // Each turn changes subject. If the router remembered the union
        // instead of the last turn, the tool set would only ever grow and the
        // saving would quietly disappear a few messages in.
        router.route("c6", "mere leads dikhao");
        router.route("c6", "Ravi ko whatsapp bhejo");
        router.route("c6", "team members dikhao");
        RoutingDecision decision = router.route("c6", "mera chatbot agent dikhao");

        assertTrue(decision.domains().contains(ToolDomain.AGENTS));
        assertTrue(decision.domains().contains(ToolDomain.TEAM), "the turn before stays");
        assertFalse(decision.domains().contains(ToolDomain.RECORDS), "three turns ago does not");
        assertFalse(decision.domains().contains(ToolDomain.WHATSAPP));
        assertTrue(decision.tools().size() < registry.all().size());
    }

    @Test
    void conversationsDoNotLeakIntoEachOther() {
        router.route("c3", "mere leads dikhao");

        RoutingDecision other = router.route("c4", "kya kya kar sakte ho");
        assertFalse(other.narrowed(), "an unrelated conversation must not inherit c3's routing");
    }

    @Test
    void aMessageWithNoConversationIdStillRoutes() {
        List<String> tools = toolsFor(null, "mere kitne leads hain");
        assertTrue(tools.contains("getRecords"));
        assertFalse(tools.contains("createAgent"));
    }

    // ---------------------------------------------------------------- fallback

    @Test
    void aMessageThatSaysNothingStaysInsideTheRequestBudget() {
        // "Send everything" stopped being a safe fallback at 77 tools: that is
        // ~8,600 tokens against a tier that allows 8,000 a minute, so an
        // unplaceable message would have failed outright instead of degrading.
        RoutingDecision decision = router.route("fresh", "kya kya kar sakte ho?");

        assertFalse(decision.narrowed());
        assertTrue(ToolRegistry.estimatedTokens(decision.tools()) <= 3500,
                "the fallback is " + ToolRegistry.estimatedTokens(decision.tools()) + " tokens");
        // Still enough to answer a vague question about the workspace.
        List<String> tools = namesOf(decision.tools());
        assertTrue(tools.contains("getDashboardSummary"));
        assertTrue(tools.contains("getRecords"));
        assertTrue(tools.contains("getMyTasks"));
        assertTrue(tools.contains("findMyFormByName"));
    }

    @Test
    void aGenerousBudgetStillFallsBackToEverything() {
        // On a tier that can carry the whole set, the original guarantee holds:
        // an unplaceable message is exactly the request the panel used to send.
        ToolRouter roomy = new ToolRouter(registry, true, ZoneId.of("Asia/Kolkata"),
                FIXED_CLOCK, 100_000);

        RoutingDecision decision = roomy.route("fresh", "kya kya kar sakte ho?");

        assertFalse(decision.narrowed());
        assertEquals(registry.all().size(), decision.tools().size());
        assertEquals(fullPrompt(), decision.systemPrompt());
    }

    @Test
    void theKillSwitchRestoresTheOldBehaviourExactly() {
        ToolRouter off = new ToolRouter(registry, false, ZoneId.of("Asia/Kolkata"), FIXED_CLOCK, 3500);

        RoutingDecision decision = off.route("c5", "mere kitne leads hain");
        assertFalse(decision.narrowed());
        assertEquals(namesOf(registry.all()), namesOf(decision.tools()));
        assertEquals(fullPrompt(), decision.systemPrompt());
    }
}
