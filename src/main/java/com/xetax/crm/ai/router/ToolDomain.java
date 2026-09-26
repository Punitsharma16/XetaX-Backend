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

    /**
     * The to-do list. Carries record lookup because a task is usually
     * attached to a record the user names rather than knows the id of.
     */
    TASKS(
            Set.of("getMyTasks", "getRecordTasks", "createTask", "completeTask", "deleteTask",
                    "getRecords", "searchRecords"),
            Set.of("task", "tasks", "todo", "todos", "reminder", "reminders", "remind",
                    "followup", "pending", "due", "deadline", "call", "calls"),
            """
            - TASKS: getMyTasks/getRecordTasks read the signed-in user's own to-do list; createTask adds one. Attach a task to a record or contact by looking it up first (searchRecords/searchMyContacts) and passing its id plus linkedName; leave both out for a personal task. dueAtIso is UTC — convert from the user's local time and never invent a date; an email/WhatsApp reminder needs one. completeTask and deleteTask ONLY on an explicit request.
            """),

    /** The user's own embeddable website chatbots. */
    AGENTS(
            Set.of("getMyAgents", "createAgent", "addAgentTextKnowledge",
                    "addAgentUrlKnowledge", "getAgentEmbedScript"),
            Set.of("agent", "agents", "chatbot", "chatbots", "bot", "bots", "website",
                    "embed", "widget", "script"),
            """
            - PUBLIC AGENTS (agents.manage): createAgent/addAgent*Knowledge/getAgentEmbedScript build the user's own embeddable website chatbot. createAgent ONLY on explicit request; after creating, give the embedScript and tell them knowledge (PDF upload is panel-only; text/URL you can add). These agents are public-facing — never put CRM data into their knowledge unless the user explicitly pastes it.
            """),

    /**
     * Money. Carries record and contact lookup because an invoice must be
     * attached to one or the other, and the user names the customer.
     */
    INVOICES(
            Set.of("getMyInvoices", "getInvoiceSummary", "getInvoiceDetails", "getInvoicesFor",
                    "createInvoice", "recordInvoicePayment", "sendInvoice", "cancelInvoice",
                    "getRecords", "searchRecords", "getMyContacts"),
            Set.of("invoice", "invoices", "bill", "bills", "billing", "payment", "payments",
                    "paid", "unpaid", "outstanding", "receipt", "gst", "tax", "revenue",
                    "collection", "balance", "paisa", "rupees"),
            """
            - INVOICES (invoices.view/manage): getInvoiceSummary answers "how much is outstanding"; getMyInvoices searches by customer name or number. createInvoice must be attached to exactly one of a recordId or a contactId — find the customer first — and is created as a DRAFT. recordInvoicePayment, sendInvoice and cancelInvoice change money or reach the customer: ONLY on an explicit request, and confirm the amounts first.
            """),

    /**
     * Bulk email. Carries form lookup because a campaign's audience is a
     * form's records addressed by one of its fields.
     */
    EMAIL(
            Set.of("getEmailStatus", "getEmailCampaigns", "getEmailCampaignDetails",
                    "createEmailCampaignDraft", "setEmailCampaignState", "getFormDetails"),
            Set.of("email", "emails", "mail", "mails", "smtp", "newsletter", "inbox",
                    "campaign", "campaigns", "bulk", "blast", "subject"),
            """
            - EMAIL CAMPAIGNS (email.campaigns): check getEmailStatus first — bulk email goes from the org's own SMTP and needs it configured. createEmailCampaignDraft needs a form slug and the field key holding the address; it creates a DRAFT and you must show the subject and body before saving. NEVER start a campaign — the user starts it from the Email Campaigns page. setEmailCampaignState can PAUSE/RESUME/CANCEL a running one, on explicit request.
            """),

    /** The appointment book. */
    BOOKINGS(
            Set.of("getBookingOverview", "getBookingSlots", "cancelBooking"),
            Set.of("booking", "bookings", "appointment", "appointments", "slot", "slots",
                    "walkin", "diary"),
            """
            - BOOKINGS (forms.view/manage): getBookingSlots answers who is coming when — dates are yyyy-MM-dd and it defaults to the next seven days. getBookingOverview has the public link and the staff. cancelBooking frees a slot ONLY on an explicit request; the CRM record the booking created stays.
            """),

    /** The document library. Carries record lookup for personalised sends. */
    DOCUMENTS(
            Set.of("getMyDocuments", "sendDocument", "getRecords", "searchRecords"),
            Set.of("document", "documents", "brochure", "brochures", "pdf", "attachment",
                    "catalogue", "catalog", "agreement", "letter"),
            """
            - DOCUMENTS (documents.view/manage): getMyDocuments lists the uploaded brochures and letters. sendDocument reaches a customer — ONLY on an explicit request; prefer passing the recordId or contactId it is for so the number/address and any {placeholder} variables come from there. Uploading a new document is panel-only.
            """),

    /** One call for the numbers the Dashboard page shows. */
    ANALYTICS(
            Set.of("getDashboardSummary"),
            Set.of("dashboard", "summary", "overview", "report", "reports", "analytics",
                    "stats", "statistics", "performance", "insights", "growth"),
            """
            - DASHBOARD: getDashboardSummary is the whole workspace in one call. Prefer it over adding several tools' numbers up by hand for broad questions like "how is my business doing".
            """),

    /** The public online menu — the restaurant pack. */
    MENU(
            Set.of("getMenuOverview", "createMenuCategory", "createMenuItem", "updateMenuItem"),
            Set.of("menu", "dish", "dishes", "restaurant", "food", "cuisine", "veg",
                    "category", "categories", "storefront"),
            """
            - ONLINE MENU (forms.view/manage): read getMenuOverview first — every category and item id comes from it, and you resolve names to ids yourself. updateMenuItem changes only what you pass, so "paneer tikka band kar do" is available=false, not a delete. Orders placed on the public page arrive as RECORDS in the linked form, so use the record tools to read them. Writes ONLY on an explicit request.
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
