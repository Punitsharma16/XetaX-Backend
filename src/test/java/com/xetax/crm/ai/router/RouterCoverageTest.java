package com.xetax.crm.ai.router;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How much of the real traffic the router can actually place.
 *
 * <p>The router only saves anything on messages it can read. If enough of
 * them fall through to the full tool set the whole exercise is overhead with
 * no payoff, and nothing about that failure is visible — the assistant keeps
 * working and the token bill keeps not moving. So the corpus below is panel
 * questions phrased the way the users phrase them, and the rate is pinned.
 *
 * <p>The generic ones at the end are separate on purpose: those SHOULD fall
 * back. "XetaX kya hai" is answered from retrieved knowledge and "haan kar do"
 * is meaningless on its own, and guessing a narrow tool set for either is
 * worse than sending everything.
 */
class RouterCoverageTest {

    /** Panel questions with a subject, and the domain each one must reach. */
    private static final Map<String, ToolDomain> PLACEABLE = new java.util.LinkedHashMap<>();

    static {
        PLACEABLE.put("mere kitne leads hain", ToolDomain.RECORDS);
        PLACEABLE.put("aaj kitne naye leads aaye", ToolDomain.RECORDS);
        PLACEABLE.put("Sales Pipeline form ke saare records dikhao", ToolDomain.RECORDS);
        PLACEABLE.put("ek naya lead add karo naam Rahul number 9876543210", ToolDomain.RECORDS);
        PLACEABLE.put("is lead ko Closed stage me move kar do", ToolDomain.RECORDS);
        PLACEABLE.put("Rahul ka record update karo budget 50 lakh", ToolDomain.RECORDS);
        PLACEABLE.put("ek Real Estate form banao", ToolDomain.FORMS);
        PLACEABLE.put("us form me ek email field add karo", ToolDomain.FORMS);
        PLACEABLE.put("form ke stages dikhao", ToolDomain.FORMS);
        PLACEABLE.put("ek naya stage banao Site Visit", ToolDomain.FORMS);
        PLACEABLE.put("mere saare forms dikhao", ToolDomain.FORMS);
        PLACEABLE.put("Sales form me kaunse fields hain", ToolDomain.FORMS);
        PLACEABLE.put("ek automation banao jo stage change pe email bheje", ToolDomain.AUTOMATIONS);
        PLACEABLE.put("meri automations dikhao", ToolDomain.AUTOMATIONS);
        PLACEABLE.put("automation ka rule badal do", ToolDomain.AUTOMATIONS);
        PLACEABLE.put("kitne contacts hain", ToolDomain.CONTACTS);
        PLACEABLE.put("Ravi ka number dhundo", ToolDomain.CONTACTS);
        PLACEABLE.put("contacts list dikhao", ToolDomain.CONTACTS);
        PLACEABLE.put("Ravi ko whatsapp bhejo", ToolDomain.WHATSAPP);
        PLACEABLE.put("whatsapp connected hai kya", ToolDomain.WHATSAPP);
        PLACEABLE.put("mere templates dikhao", ToolDomain.WHATSAPP);
        PLACEABLE.put("ek campaign draft banao", ToolDomain.WHATSAPP);
        PLACEABLE.put("whats app ka status batao", ToolDomain.WHATSAPP);
        PLACEABLE.put("kal 3 baje meeting laga do Rahul ke saath", ToolDomain.MEETINGS);
        PLACEABLE.put("meri meetings dikhao", ToolDomain.MEETINGS);
        PLACEABLE.put("meeting ka link bhej do", ToolDomain.MEETINGS);
        PLACEABLE.put("meeting cancel kar do", ToolDomain.MEETINGS);
        PLACEABLE.put("mere team members dikhao", ToolDomain.TEAM);
        PLACEABLE.put("ek naya user banao sales role ke saath", ToolDomain.TEAM);
        PLACEABLE.put("Rahul ke saare records Priya ko transfer kar do", ToolDomain.TEAM);
        PLACEABLE.put("roles aur permissions dikhao", ToolDomain.TEAM);
        // Verbatim from the conversation where the assistant said it had no
        // way to create a task.
        PLACEABLE.put("create a task to call on the number 9034908545 for tomorrow 10 am",
                ToolDomain.TASKS);
        PLACEABLE.put("mere pending tasks dikhao", ToolDomain.TASKS);
        PLACEABLE.put("kal ke liye ek reminder laga do", ToolDomain.TASKS);
        PLACEABLE.put("is record pr task bna do", ToolDomain.TASKS);
        PLACEABLE.put("mere pending invoices dikhao", ToolDomain.INVOICES);
        PLACEABLE.put("kitna paisa aana baaki hai", ToolDomain.INVOICES);
        PLACEABLE.put("Rahul ka invoice banao 2 AC 45000 ka", ToolDomain.INVOICES);
        PLACEABLE.put("is invoice pr 10000 payment aa gaya", ToolDomain.INVOICES);
        PLACEABLE.put("email campaign draft banao", ToolDomain.EMAIL);
        PLACEABLE.put("mera smtp connected hai kya", ToolDomain.EMAIL);
        PLACEABLE.put("kal ki bookings dikhao", ToolDomain.BOOKINGS);
        PLACEABLE.put("ye appointment cancel kar do", ToolDomain.BOOKINGS);
        PLACEABLE.put("mere documents dikhao", ToolDomain.DOCUMENTS);
        PLACEABLE.put("Rahul ko brochure bhej do", ToolDomain.DOCUMENTS);
        PLACEABLE.put("is mahine ka summary do", ToolDomain.ANALYTICS);
        PLACEABLE.put("business kaisa chal raha hai report do", ToolDomain.ANALYTICS);
        PLACEABLE.put("paneer tikka aaj band kar do menu se", ToolDomain.MENU);
        PLACEABLE.put("menu me ek nayi dish add karo", ToolDomain.MENU);
        PLACEABLE.put("website ke liye chatbot banao", ToolDomain.AGENTS);
        PLACEABLE.put("agent ka embed script do", ToolDomain.AGENTS);
        PLACEABLE.put("mere agents dikhao", ToolDomain.AGENTS);
    }

    /** Questions with no subject of their own — the full set is the right answer. */
    private static final List<String> GENERIC = List.of(
            "haan kar do",
            "theek hai",
            "kya kya kar sakte ho",
            "hello",
            "XetaX kya hai",
            "ye kaise kaam karta hai");

    @Test
    void everyQuestionWithASubjectReachesItsDomain() {
        List<String> missed = new ArrayList<>();
        for (Map.Entry<String, ToolDomain> each : PLACEABLE.entrySet()) {
            if (!ToolRouter.match(each.getKey()).contains(each.getValue())) {
                missed.add(each.getValue() + " <- \"" + each.getKey() + "\"");
            }
        }
        assertEquals(List.of(), missed, "these messages no longer reach their tools");
    }

    @Test
    void aQuestionWithNoSubjectFallsBackInsteadOfGuessing() {
        for (String message : GENERIC) {
            assertTrue(ToolRouter.match(message).isEmpty(),
                    "\"" + message + "\" was guessed at instead of getting the full tool set");
        }
    }

    @Test
    void mostRealTrafficIsPlaceable() {
        int placed = 0;
        for (String message : PLACEABLE.keySet()) {
            if (!ToolRouter.match(message).isEmpty()) {
                placed++;
            }
        }
        // Measured at 52/52 here and 84% across the whole corpus including the
        // generic questions. Well below this and the router stops paying for
        // itself.
        assertTrue(placed >= PLACEABLE.size() * 9 / 10,
                "only " + placed + " of " + PLACEABLE.size() + " messages could be routed");
    }

    @Test
    void routingStaysNarrowEvenWhenAMessageMentionsTwoThings() {
        // A word that fits two domains costs one extra domain, never the set.
        Set<ToolDomain> domains = ToolRouter.match("meeting ka link bhej do");
        assertTrue(domains.contains(ToolDomain.MEETINGS));
        assertTrue(domains.size() <= 3,
                "a single sentence pulled in " + domains.size() + " domains: " + domains);
    }
}
