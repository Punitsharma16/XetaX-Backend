package com.xetax.crm.ai.router;

import java.util.Set;

/**
 * One area of the panel the assistant works in.
 *
 * <p>Every request used to carry all 49 tool schemas (~5,555 tokens) plus the
 * whole system prompt, whatever the user asked. On the free Groq tier that is
 * an 8,000 token/minute cap spent before the question is even read, which is
 * what the panel's production 413s were. A domain names the tools and the
 * prompt paragraph that one kind of question actually needs, so only those
 * travel.
 *
 * <p>The tool lists are deliberately generous rather than minimal. A tool is
 * listed in every domain that can reach it, because a domain that is missing
 * one step of a chain is a silent capability loss — the voice agent lost
 * "sales pipeline ke record dikhao" exactly that way, by dropping the form
 * lookup that {@code RecordTools} needs to turn a name into a formId.
 *
 * <p>Order matters: {@link AssistantPrompt} appends the sections in enum
 * order, and with every domain selected the result is byte-for-byte the
 * prompt the assistant shipped with. {@code AssistantPromptTest} holds that.
 */
public enum ToolDomain {

    /** Building the workspace itself: forms, their fields and their stages. */
    FORMS(
            Set.of("createForm", "updateForm", "getFormDetails", "getDefaultStage",
                    "getFinalStages", "getFormOverview", "createField", "updateField",
                    "createStage", "updateStage"),
            Set.of("form", "forms", "formo", "field", "fields", "feild", "stage", "stages",
                    "pipeline", "dropdown", "slug", "column", "columns", "schema"),
            ""),

    /** The rows inside those forms — leads, deals, enquiries, customers. */
    RECORDS(
            Set.of("getRecords", "searchRecords", "getRecordDetails", "updateRecord",
                    "moveRecordStage", "createRecord", "getDefaultStage", "getFormOverview"),
            Set.of("record", "records", "lead", "leads", "deal", "deals", "entry", "entries",
                    "row", "rows", "data", "customer", "customers", "client", "clients",
                    "enquiry", "enquiries", "inquiry", "inquiries", "prospect", "prospects",
                    "move", "shift"),
            ""),

    /** Rules that fire on a record — needs the form's fields and stages too. */
    AUTOMATIONS(
            Set.of("getMyAutomations", "getAutomationDetails", "updateAutomation",
                    "createAutomation", "getFormDetails", "getDefaultStage",
                    "getFinalStages", "getFormOverview"),
            Set.of("automation", "automations", "automate", "automated", "rule", "rules",
                    "trigger", "triggers", "workflow", "workflows"),
            ""),

    /** The address book. */
    CONTACTS(
            Set.of("getMyContacts"),
            Set.of("contact", "contacts", "phonebook", "number", "numbers", "mobile",
                    "customer", "customers", "client", "clients"),
            """
            - CONTACTS (contacts.view): getMyContacts/searchMyContacts read the user's address book — counts, lists and lookups by name, number, email or company. Read-only: to message or edit a contact, point the user at the Contacts page.
            """),

    /**
     * Messaging. Carries the address book (a user says "Ravi ko bhejo", not a
     * phone number) and getFormDetails, because createWhatsAppCampaignDraft
     * takes a form SLUG and a phone fieldKey.
     */
    WHATSAPP(
            Set.of("getWhatsAppStatus", "getWhatsAppTemplates", "getWhatsAppCampaigns",
                    "getWhatsAppCampaignDetails", "sendWhatsAppMessage",
                    "createWhatsAppCampaignDraft", "getMyContacts", "getFormDetails"),
            Set.of("whatsapp", "whatapp", "wa", "wp", "message", "messages", "msg", "sms",
                    "bhejo", "bhej", "bheje", "bhejna", "bheju", "send", "sent", "broadcast",
                    "campaign", "campaigns", "template", "templates", "chat"),
            """
            - WHATSAPP: read tools (status/templates/campaigns) work like other read tools. sendWhatsAppMessage ONLY when the user explicitly asks to send a message NOW — confirm number and exact text first, never proactively. createWhatsAppCampaignDraft creates a DRAFT only; NEVER start a campaign — the user starts it from the Campaigns page. Never reveal tokens or webhook internals.
            """),

    /** Video meetings. Carries the address book for the guest's number/email. */
    MEETINGS(
            Set.of("getMyMeetings", "createMeeting", "sendMeetingLink", "cancelMeeting",
                    "getMyContacts"),
            Set.of("meeting", "meetings", "meet", "schedule", "scheduled", "appointment",
                    "appointments", "call", "calls", "video", "demo", "reschedule", "cancel",
                    "baithak", "milna"),
            """
            - MEETINGS: createMeeting makes an instant or scheduled video meeting and returns the guest link. sendMeetingLink ONLY on explicit user request — channel WHATSAPP needs WhatsApp connected (else offer EMAIL). Never invent meeting times; if the user says a relative time (kal 3 baje) convert it, and when ambiguous ask.
            """),

    /**
     * Members, roles and reassignment. Carries the record read tools because
     * transferRecords takes record ids the user names, not knows.
     */
    TEAM(
            Set.of("getPermissionCatalog", "getTeamRoles", "createTeamRole", "getTeamMembers",
                    "createTeamMember", "changeMemberRole", "transferAllRecords",
                    "transferRecords", "getRecords", "searchRecords"),
            Set.of("team", "teams", "member", "members", "role", "roles", "permission",
                    "permissions", "user", "users", "staff", "employee", "employees",
                    "transfer", "assign", "assigned", "assignee", "invite", "password"),
            """
            - TEAM & ROLES: team tools need the team.manage permission (owners always have it). createTeamRole/createTeamMember/changeMemberRole/transferAllRecords ONLY on explicit request — for roles confirm the exact permission keys first (getPermissionCatalog), for members return the one-time temporary password to the user. If a tool returns a permission error, tell the user their role doesn't allow it.
            """),

    /** The user's own embeddable website chatbots. */
    AGENTS(
            Set.of("getMyAgents", "createAgent", "addAgentTextKnowledge",
                    "addAgentUrlKnowledge", "getAgentEmbedScript"),
            Set.of("agent", "agents", "chatbot", "chatbots", "bot", "bots", "website",
                    "embed", "widget", "script"),
            """
            - PUBLIC AGENTS (agents.manage): createAgent/addAgent*Knowledge/getAgentEmbedScript build the user's own embeddable website chatbot. createAgent ONLY on explicit request; after creating, give the embedScript and tell them knowledge (PDF upload is panel-only; text/URL you can add). These agents are public-facing — never put CRM data into their knowledge unless the user explicitly pastes it.
            """);

    /**
     * Sent with EVERY request, whatever the routing says.
     *
     * <p>Users speak in names and every write tool is id-based, so name->id
     * resolution has to survive any routing decision. Five schemas is a cheap
     * insurance premium against the whole class of "the assistant suddenly
     * can't find my form" bug.
     */
    public static final Set<String> ALWAYS_AVAILABLE = Set.of(
            "findMyFormByName", "getMyForms", "getFormFields", "getFormStages",
            "searchMyContacts");

    private final Set<String> tools;
    private final Set<String> keywords;
    private final String promptSection;

    ToolDomain(Set<String> tools, Set<String> keywords, String promptSection) {
        this.tools = tools;
        this.keywords = keywords;
        this.promptSection = promptSection;
    }

    /** Tool names this domain adds on top of {@link #ALWAYS_AVAILABLE}. */
    public Set<String> tools() {
        return this.tools;
    }

    /** Words that point a message at this domain (lowercase, already stemmed by hand). */
    public Set<String> keywords() {
        return this.keywords;
    }

    /** The system-prompt paragraph for this domain; empty when its rules live in the core prompt. */
    public String promptSection() {
        return this.promptSection;
    }
}
