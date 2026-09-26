package com.xetax.crm.ai.router;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private final ToolRegistry registry = new ToolRegistry(ToolBeans.instances());

    private static List<String> namesOf(List<ToolCallback> callbacks) {
        List<String> names = new ArrayList<>();
        for (ToolCallback callback : callbacks) {
            names.add(callback.getToolDefinition().name());
        }
        return names;
    }

    @Test
    void itFindsEveryToolTheAssistantHad() {
        assertEquals(77, registry.all().size());
        assertTrue(registry.names().contains("createRecord"));
        assertTrue(registry.names().contains("createTask"));
        assertTrue(registry.names().contains("createInvoice"));
        assertTrue(registry.names().contains("getBookingSlots"));
        assertTrue(registry.names().contains("getDashboardSummary"));
        assertTrue(registry.names().contains("sendWhatsAppMessage"));
        assertTrue(registry.names().contains("getAgentEmbedScript"));
    }

    @Test
    void everyDomainSelectedIsTheWholeToolSet() {
        // The safety property the fallback depends on: routing to everything
        // has to be the same request the panel sent before the router existed.
        assertEquals(namesOf(registry.all()),
                namesOf(registry.forDomains(EnumSet.allOf(ToolDomain.class))));
    }

    @Test
    void oneDomainSendsThatDomainAndNameResolution() {
        List<String> records = namesOf(registry.forDomains(Set.of(ToolDomain.RECORDS)));

        assertTrue(records.contains("createRecord"));
        assertTrue(records.contains("searchRecords"));
        // Always available, whatever the routing.
        assertTrue(records.contains("findMyFormByName"));
        assertTrue(records.contains("getFormFields"));
        // Everything the question cannot possibly need stays home.
        assertFalse(records.contains("sendWhatsAppMessage"));
        assertFalse(records.contains("createTeamMember"));
        assertFalse(records.contains("createAgent"));
        assertFalse(records.contains("createAutomation"));
    }

    @Test
    void narrowRoutingIsWorthDoing() {
        // If a routed request were not much smaller than the full set there
        // would be no reason to take the routing risk at all.
        int full = registry.all().size();
        for (ToolDomain domain : ToolDomain.values()) {
            int routed = registry.forDomains(Set.of(domain)).size();
            assertTrue(routed <= full / 2,
                    domain + " alone sends " + routed + " of " + full + " tools");
        }
    }

    @Test
    void noDomainAtAllStillLeavesNameResolution() {
        List<String> none = namesOf(registry.forDomains(Set.of()));
        assertEquals(ToolDomain.ALWAYS_AVAILABLE.size(), none.size());
        assertTrue(none.containsAll(ToolDomain.ALWAYS_AVAILABLE));
    }

    @Test
    void theOrderOfToolsOnTheWireDoesNotChangeBetweenRequests() {
        // Same routing must serialize identically, or prompt caching upstream
        // misses on every call.
        assertEquals(namesOf(registry.forDomains(Set.of(ToolDomain.WHATSAPP, ToolDomain.RECORDS))),
                namesOf(registry.forDomains(Set.of(ToolDomain.RECORDS, ToolDomain.WHATSAPP))));
    }

    @Test
    void twoToolsWithTheSameNameRefuseToBoot() {
        // One tool shadowing another, with the winner decided by scan order,
        // is not something to find out about in production. Spring AI rejects
        // it first and the registry has its own backstop; either is fine, the
        // point is that the application does not start.
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new ToolRegistry(List.of(new Duplicated(), new Duplicated())));
        assertTrue(String.valueOf(thrown.getMessage()).contains("getMyForms"), thrown.toString());
    }

    /** A second bean exposing the same tool name — the shadowing case. */
    static class Duplicated {
        @org.springframework.ai.tool.annotation.Tool(description = "A test tool.")
        public String getMyForms() {
            return "";
        }
    }
}
