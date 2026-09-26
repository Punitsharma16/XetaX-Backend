package com.xetax.crm.ai.router;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The routing map against the code it claims to describe.
 *
 * <p>A tool that no domain lists is a capability the assistant lost the day
 * the router shipped, and nothing about it fails loudly — the model simply
 * stops being able to do that one thing. A domain that lists a name no tool
 * answers to is the same bug from the other side, usually a rename. Both are
 * caught here, in CI, rather than by a user in production.
 */
class ToolRoutingMapTest {

    /** Every {@code @Tool} method name on the classes the assistant is given. */
    private static Set<String> declaredToolNames() {
        Set<String> names = new TreeSet<>();
        for (Class<?> type : ToolBeans.classes()) {
            for (Method method : type.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null) {
                    names.add(tool.name().isEmpty() ? method.getName() : tool.name());
                }
            }
        }
        return names;
    }

    private static Set<String> routedNames() {
        Set<String> names = new TreeSet<>(ToolDomain.ALWAYS_AVAILABLE);
        for (ToolDomain domain : ToolDomain.values()) {
            names.addAll(domain.tools());
        }
        return names;
    }

    @Test
    void everyToolTheAssistantHasIsRoutedSomewhere() {
        Set<String> unrouted = new TreeSet<>(declaredToolNames());
        unrouted.removeAll(routedNames());
        assertTrue(unrouted.isEmpty(),
                "These tools are in no ToolDomain, so a routed request can never reach them: "
                        + unrouted);
    }

    @Test
    void everyRoutedNameIsARealTool() {
        Set<String> phantom = new TreeSet<>(routedNames());
        phantom.removeAll(declaredToolNames());
        assertTrue(phantom.isEmpty(),
                "ToolDomain routes to tools that do not exist (renamed or removed?): " + phantom);
    }

    @Test
    void theAssistantStillHasTheSameFortyNineTools() {
        // Pins the count the token budget was measured against: 49 schemas,
        // ~5,555 tokens. A change here should be a deliberate one.
        assertEquals(49, declaredToolNames().size(), declaredToolNames().toString());
    }

    @Test
    void nameResolutionSurvivesEveryRoutingDecision() {
        // The failure this guards against: a narrow tool set that can create a
        // record but can no longer turn "sales pipeline" into a formId.
        assertTrue(ToolDomain.ALWAYS_AVAILABLE.contains("findMyFormByName"));
        assertTrue(ToolDomain.ALWAYS_AVAILABLE.contains("getMyForms"));
        assertTrue(ToolDomain.ALWAYS_AVAILABLE.contains("getFormFields"));
        assertTrue(ToolDomain.ALWAYS_AVAILABLE.contains("getFormStages"));
        assertTrue(ToolDomain.ALWAYS_AVAILABLE.contains("searchMyContacts"));
    }

    @Test
    void eachDomainCarriesWhatItsOwnToolsDependOn() {
        // createRecord needs the form's stages and fields; both are always on.
        assertTrue(ToolDomain.RECORDS.tools().contains("createRecord"));
        assertTrue(ToolDomain.RECORDS.tools().contains("getDefaultStage"));

        // createWhatsAppCampaignDraft takes a form SLUG and a phone fieldKey,
        // and "Ravi ko bhejo" needs the address book to find a number.
        assertTrue(ToolDomain.WHATSAPP.tools().contains("getFormDetails"));
        assertTrue(ToolDomain.WHATSAPP.tools().contains("getMyContacts"));

        // sendMeetingLink needs the guest's number or email.
        assertTrue(ToolDomain.MEETINGS.tools().contains("getMyContacts"));

        // transferRecords takes record ids the user names rather than knows.
        assertTrue(ToolDomain.TEAM.tools().contains("searchRecords"));

        // An automation's action targets a field and a stage of its form.
        assertTrue(ToolDomain.AUTOMATIONS.tools().contains("getFinalStages"));
        assertTrue(ToolDomain.AUTOMATIONS.tools().contains("getDefaultStage"));
    }

    @Test
    void noTwoToolsShareAName() {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new TreeSet<>();
        for (Class<?> type : ToolBeans.classes()) {
            for (Method method : type.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null) {
                    String name = tool.name().isEmpty() ? method.getName() : tool.name();
                    if (!seen.add(name)) {
                        duplicates.add(name);
                    }
                }
            }
        }
        assertTrue(duplicates.isEmpty(), "Tool names must be unique: " + duplicates);
    }
}
