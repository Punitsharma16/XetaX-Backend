package com.xetax.crm.ai.router;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void theToolCountIsWhatWeThinkItIs() {
        // 49 when the router shipped (~5,555 tokens of schema), then tasks,
        // invoices, email campaigns, bookings, documents, the dashboard and
        // the online menu — the panel features that had no tools at all.
        // Pinned so growth stays a decision rather than a drift; the router
        // is what makes 77 affordable, since a request only carries its own
        // domain.
        assertEquals(77, declaredToolNames().size(), declaredToolNames().toString());
    }

    @Test
    void everyPanelFeatureTheUserCanOpenHasTools() {
        // Walked the panel's routes against the tool list. These are the ones
        // that had nothing behind them.
        assertTrue(declaredToolNames().contains("getMyInvoices"), "Invoices");
        assertTrue(declaredToolNames().contains("getEmailCampaigns"), "Email Campaigns");
        assertTrue(declaredToolNames().contains("getBookingSlots"), "Bookings");
        assertTrue(declaredToolNames().contains("getMyDocuments"), "Documents");
        assertTrue(declaredToolNames().contains("getMenuOverview"), "Online Menu");
        assertTrue(declaredToolNames().contains("getDashboardSummary"), "Dashboard");
    }

    @Test
    void nothingCanStartABulkSendOnItsOwn() {
        // The rule the WhatsApp tools already follow: the assistant drafts a
        // campaign, the person whose sender reputation is at stake starts it.
        assertFalse(declaredToolNames().contains("startEmailCampaign"));
        assertFalse(declaredToolNames().contains("startWhatsAppCampaign"));
    }

    @Test
    void theAssistantCanWorkWithTasks() {
        // It used to answer "I don't have a way to create a task directly
        // from this interface" while the panel had a whole Tasks section.
        assertTrue(declaredToolNames().contains("createTask"));
        assertTrue(declaredToolNames().contains("getMyTasks"));
        assertTrue(ToolDomain.TASKS.tools().contains("createTask"));
        // A task is usually hung off a record the user names, not an id.
        assertTrue(ToolDomain.TASKS.tools().contains("searchRecords"));
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
