package com.xetax.crm.template.service;

import java.util.List;
import java.util.Map;

/**
 * The built-in business templates. Pure data — the apply step feeds these
 * into the SAME Form/Field/Stage/Automation services the UI uses, so nothing
 * template-created is special. Automations are created INACTIVE: the user
 * reviews the phone/email field mapping and switches them on.
 */
public final class FormTemplateCatalog {

    public record TField(String label, String fieldKey, String fieldType,
                         boolean required, String optionsJson) {}

    public record TStatus(String name, String color, boolean isDefault) {}

    public record TStage(String name, String code, int sequence,
                         boolean isDefault, boolean isFinal, String color,
                         List<TStatus> statuses) {}

    /**
     * triggerStageCode scopes STAGE_CHANGED; for STATUS_CHANGED it names the
     * stage whose status (triggerStatusName) the rule waits for.
     */
    public record TAutomation(String name, String trigger, String triggerStageCode,
                              String triggerStatusName,
                              String actionType, String actionFieldKey, String actionValue,
                              String emailSubject, String emailMessage, String note) {}

    public record Template(String key, String name, String icon, String color,
                           String tagline, String description,
                           List<TField> fields, List<TStage> stages,
                           List<TAutomation> automations) {}

    public static final Map<String, Template> ALL = Map.of(
        "sales", new Template("sales", "Sales Pipeline", "bi-graph-up-arrow", "#4f46e5",
            "Leads se deals tak — kuch bhi na chhoote",
            "Naye leads capture karo, follow-up karo, aur har deal ko Won tak track karo.",
            List.of(
                new TField("Name", "name", "TEXT", true, null),
                new TField("Phone", "phone", "PHONE", true, null),
                new TField("Email", "email", "EMAIL", false, null),
                new TField("Budget", "budget", "NUMBER", false, null),
                new TField("Source", "source", "SELECT", false,
                        "[\"Website\",\"WhatsApp\",\"Referral\",\"Walk-in\"]"),
                new TField("Notes", "notes", "TEXTAREA", false, null)),
            List.of(
                new TStage("New Lead", "new", 1, true, false, "#6366f1", List.of()),
                new TStage("Contacted", "contacted", 2, false, false, "#0ea5e9", List.of(
                        new TStatus("Ringing", "#0ea5e9", true),
                        new TStatus("Call back", "#f59e0b", false),
                        new TStatus("Not reachable", "#ef4444", false))),
                new TStage("Qualified", "qualified", 3, false, false, "#f59e0b", List.of()),
                new TStage("Proposal", "proposal", 4, false, false, "#a855f7", List.of(
                        new TStatus("Sent", "#a855f7", true),
                        new TStatus("Negotiating", "#f59e0b", false))),
                new TStage("Won", "won", 5, false, true, "#22c55e", List.of()),
                new TStage("Lost", "lost", 6, false, true, "#ef4444", List.of())),
            List.of(
                new TAutomation("Welcome message", "RECORD_CREATED", null, null, "SEND_WHATSAPP",
                        "phone", null, null,
                        "Hi {name}! Aapki enquiry mil gayi hai — hamari team jald contact karegi.",
                        "Naya lead aate hi WhatsApp welcome"),
                new TAutomation("Won - thank you", "STAGE_CHANGED", "won", null, "SEND_WHATSAPP",
                        "phone", null, null,
                        "Congratulations {name}! Aapka order confirm ho gaya. Dhanyavaad!",
                        "Deal Won hote hi thank-you message"),
                new TAutomation("Not reachable - follow-up", "STATUS_CHANGED", "contacted", "Not reachable",
                        "SEND_WHATSAPP", "phone", null, null,
                        "{name} ji, humne aapse sampark karne ki koshish ki. Jab free hon, reply kar dein — hum turant call karenge.",
                        "Status 'Not reachable' hote hi customer ko soft nudge"),
                new TAutomation("Negotiation nudge", "STATUS_CHANGED", "proposal", "Negotiating",
                        "SEND_WHATSAPP", "phone", null, null,
                        "{name} ji, proposal par koi sawaal ho to batayein — best price ke liye hum taiyar hain!",
                        "Status 'Negotiating' par deal aage badhane wala message"))),

        "hospital", new Template("hospital", "Hospital / Clinic (OPD)", "bi-heart-pulse", "#dc2626",
            "Patient registration se discharge tak",
            "Patients register karo, appointments track karo, aur har step par unhe update rakho.",
            List.of(
                new TField("Patient Name", "patient_name", "TEXT", true, null),
                new TField("Phone", "phone", "PHONE", true, null),
                new TField("Age", "age", "NUMBER", false, null),
                new TField("Department", "department", "SELECT", false,
                        "[\"General\",\"Ortho\",\"Cardio\",\"Pediatrics\",\"ENT\"]"),
                new TField("Doctor", "doctor", "TEXT", false, null),
                new TField("Appointment Date", "appointment_date", "DATE", false, null)),
            List.of(
                new TStage("Registered", "registered", 1, true, false, "#6366f1", List.of()),
                new TStage("Waiting", "waiting", 2, false, false, "#f59e0b", List.of(
                        new TStatus("In Queue", "#f59e0b", true),
                        new TStatus("Called In", "#0ea5e9", false))),
                new TStage("In Consultation", "consultation", 3, false, false, "#0ea5e9", List.of()),
                new TStage("Tests / Lab", "tests", 4, false, false, "#a855f7", List.of(
                        new TStatus("Sample Taken", "#a855f7", true),
                        new TStatus("Report Ready", "#22c55e", false))),
                new TStage("Discharged", "discharged", 5, false, true, "#22c55e", List.of())),
            List.of(
                new TAutomation("Registration confirm", "RECORD_CREATED", null, null, "SEND_WHATSAPP",
                        "phone", null, null,
                        "{patient_name} ji, aapka registration ho gaya hai. Appointment: {appointment_date}.",
                        "Register hote hi patient ko confirmation"),
                new TAutomation("Lab instructions", "STAGE_CHANGED", "tests", null, "SEND_WHATSAPP",
                        "phone", null, null,
                        "{patient_name} ji, lab test ke liye pahunchein. Reports ready hone par batayenge.",
                        "Tests stage par instructions"),
                new TAutomation("Report ready", "STATUS_CHANGED", "tests", "Report Ready",
                        "SEND_WHATSAPP", "phone", null, null,
                        "{patient_name} ji, aapki report ready hai — collect kar sakte hain ya doctor se milein.",
                        "Status 'Report Ready' hote hi patient ko khabar"))),

        "restaurant", new Template("restaurant", "Restaurant Orders", "bi-shop", "#ea580c",
            "Order aaya, bana, pahucha — sab track",
            "Har order ki live journey — kitchen se customer tak, WhatsApp updates ke saath.",
            List.of(
                new TField("Customer Name", "customer_name", "TEXT", true, null),
                new TField("Phone", "phone", "PHONE", true, null),
                new TField("Order Items", "order_items", "TEXTAREA", true, null),
                new TField("Amount", "amount", "NUMBER", false, null),
                new TField("Order Type", "order_type", "SELECT", false,
                        "[\"Dine-in\",\"Takeaway\",\"Delivery\"]"),
                new TField("Address", "address", "TEXTAREA", false, null)),
            List.of(
                new TStage("Placed", "placed", 1, true, false, "#6366f1", List.of()),
                new TStage("Confirmed", "confirmed", 2, false, false, "#0ea5e9", List.of()),
                new TStage("Preparing", "preparing", 3, false, false, "#f59e0b", List.of(
                        new TStatus("In Kitchen", "#f59e0b", true),
                        new TStatus("Delayed", "#ef4444", false))),
                new TStage("Ready", "ready", 4, false, false, "#a855f7", List.of(
                        new TStatus("Awaiting Pickup", "#a855f7", true),
                        new TStatus("Out for Delivery", "#0ea5e9", false))),
                new TStage("Completed", "completed", 5, false, true, "#22c55e", List.of()),
                new TStage("Cancelled", "cancelled", 6, false, true, "#ef4444", List.of())),
            List.of(
                new TAutomation("Order received", "RECORD_CREATED", null, null, "SEND_WHATSAPP",
                        "phone", null, null,
                        "Order mil gaya! Total: Rs {amount}. Jald confirm karte hain. 🍽️",
                        "Order aate hi customer ko confirmation"),
                new TAutomation("Order ready", "STAGE_CHANGED", "ready", null, "SEND_WHATSAPP",
                        "phone", null, null,
                        "{customer_name} ji, aapka order ready hai!",
                        "Ready hote hi customer ko message"),
                new TAutomation("Order delayed - sorry", "STATUS_CHANGED", "preparing", "Delayed",
                        "SEND_WHATSAPP", "phone", null, null,
                        "{customer_name} ji, order me thoda extra time lag raha hai — jaldi hi ready hoga. Sorry! 🙏",
                        "Status 'Delayed' par customer ko pehle hi bata do"),
                new TAutomation("Out for delivery", "STATUS_CHANGED", "ready", "Out for Delivery",
                        "SEND_WHATSAPP", "phone", null, null,
                        "{customer_name} ji, aapka order raste me hai! 🛵",
                        "Delivery nikalte hi live update"))),

        "inventory", new Template("inventory", "Inventory / Procurement", "bi-box-seam", "#0891b2",
            "Stock request se shelf tak",
            "Purchase requests approve karo, orders track karo, aur stock apne aap update hota rahe.",
            List.of(
                new TField("Item Name", "item_name", "TEXT", true, null),
                new TField("SKU", "sku", "TEXT", false, null),
                new TField("Quantity", "quantity", "NUMBER", true, null),
                new TField("Supplier", "supplier", "TEXT", false, null),
                new TField("Supplier Email", "supplier_email", "EMAIL", false, null),
                new TField("Current Stock", "current_stock", "NUMBER", false, null)),
            List.of(
                new TStage("Requested", "requested", 1, true, false, "#6366f1", List.of()),
                new TStage("Approved", "approved", 2, false, false, "#0ea5e9", List.of()),
                new TStage("Ordered", "ordered", 3, false, false, "#f59e0b", List.of(
                        new TStatus("PO Sent", "#f59e0b", true),
                        new TStatus("Partially Received", "#0ea5e9", false),
                        new TStatus("Delayed", "#ef4444", false))),
                new TStage("Received", "received", 4, false, false, "#a855f7", List.of()),
                new TStage("Stocked", "stocked", 5, false, true, "#22c55e", List.of()),
                new TStage("Rejected", "rejected", 6, false, true, "#ef4444", List.of())),
            List.of(
                new TAutomation("PO email to supplier", "STAGE_CHANGED", "ordered", null, "SEND_EMAIL",
                        "supplier_email", null,
                        "Purchase Order - {item_name}",
                        "Order: {quantity} x {item_name} (SKU {sku}). Kindly confirm delivery date.",
                        "Ordered stage par supplier ko PO email"),
                new TAutomation("Stock update on receive", "STAGE_CHANGED", "received", null, "ADJUST_FIELD",
                        "current_stock", "1", null, null,
                        "Received par stock badhao (quantity apne hisaab se adjust karein)"),
                new TAutomation("Supplier delay reminder", "STATUS_CHANGED", "ordered", "Delayed",
                        "SEND_EMAIL", "supplier_email", null,
                        "Reminder: PO pending - {item_name}",
                        "Our order of {quantity} x {item_name} (SKU {sku}) is past the expected date. Please share the delivery status.",
                        "Status 'Delayed' par supplier ko reminder email"))),

        "support", new Template("support", "Customer Support Tickets", "bi-headset", "#7c3aed",
            "Har complaint ka hisaab, har customer khush",
            "Tickets capture karo, priority par kaam karo, aur resolve hote hi customer ko batao.",
            List.of(
                new TField("Customer Name", "customer_name", "TEXT", true, null),
                new TField("Phone", "phone", "PHONE", true, null),
                new TField("Email", "email", "EMAIL", false, null),
                new TField("Issue", "issue", "TEXTAREA", true, null),
                new TField("Priority", "priority", "SELECT", false,
                        "[\"Low\",\"Medium\",\"High\",\"Urgent\"]")),
            List.of(
                new TStage("Open", "open", 1, true, false, "#6366f1", List.of()),
                new TStage("In Progress", "in_progress", 2, false, false, "#f59e0b", List.of(
                        new TStatus("Being Worked", "#f59e0b", true),
                        new TStatus("Escalated", "#ef4444", false))),
                new TStage("Waiting on Customer", "waiting", 3, false, false, "#0ea5e9", List.of(
                        new TStatus("Info Needed", "#0ea5e9", true),
                        new TStatus("No Response", "#ef4444", false))),
                new TStage("Resolved", "resolved", 4, false, true, "#22c55e", List.of())),
            List.of(
                new TAutomation("Ticket confirmation", "RECORD_CREATED", null, null, "SEND_WHATSAPP",
                        "phone", null, null,
                        "{customer_name} ji, aapki complaint register ho gayi hai. Team jald sampark karegi.",
                        "Ticket bante hi confirmation"),
                new TAutomation("Resolved message", "STAGE_CHANGED", "resolved", null, "SEND_WHATSAPP",
                        "phone", null, null,
                        "{customer_name} ji, aapka issue resolve ho gaya hai. Dhanyavaad!",
                        "Resolve hote hi customer ko khabar"),
                new TAutomation("Escalation update", "STATUS_CHANGED", "in_progress", "Escalated",
                        "SEND_WHATSAPP", "phone", null, null,
                        "{customer_name} ji, aapka issue hamari senior team dekh rahi hai — jald update denge.",
                        "Status 'Escalated' par customer ko bharosa"),
                new TAutomation("Waiting - customer nudge", "STATUS_CHANGED", "waiting", "No Response",
                        "SEND_WHATSAPP", "phone", null, null,
                        "{customer_name} ji, aapke jawab ka intezar hai — reply milte hi hum aage badhenge.",
                        "Status 'No Response' par yaad dilana"))),

        "real_estate", new Template("real_estate", "Real Estate Leads", "bi-buildings", "#16a34a",
            "Site visit se registry tak ka safar",
            "Property enquiries manage karo — budget, location, site visits aur booking sab ek jagah.",
            List.of(
                new TField("Client Name", "client_name", "TEXT", true, null),
                new TField("Phone", "phone", "PHONE", true, null),
                new TField("Budget", "budget", "NUMBER", false, null),
                new TField("Property Type", "property_type", "SELECT", false,
                        "[\"1BHK\",\"2BHK\",\"3BHK\",\"Villa\",\"Plot\",\"Commercial\"]"),
                new TField("Preferred Location", "location", "TEXT", false, null),
                new TField("Site Visit Date", "visit_date", "DATE", false, null)),
            List.of(
                new TStage("Enquiry", "enquiry", 1, true, false, "#6366f1", List.of()),
                new TStage("Site Visit", "site_visit", 2, false, false, "#0ea5e9", List.of(
                        new TStatus("Scheduled", "#0ea5e9", true),
                        new TStatus("Visited", "#22c55e", false),
                        new TStatus("No Show", "#ef4444", false))),
                new TStage("Negotiation", "negotiation", 3, false, false, "#f59e0b", List.of(
                        new TStatus("Offer Made", "#f59e0b", true),
                        new TStatus("Considering", "#0ea5e9", false))),
                new TStage("Token / Booking", "booking", 4, false, false, "#a855f7", List.of()),
                new TStage("Registry Done", "registry", 5, false, true, "#22c55e", List.of()),
                new TStage("Dropped", "dropped", 6, false, true, "#ef4444", List.of())),
            List.of(
                new TAutomation("Enquiry welcome", "RECORD_CREATED", null, null, "SEND_WHATSAPP",
                        "phone", null, null,
                        "Namaste {client_name} ji! Aapki property enquiry mili — {property_type}, {location}. Team jald call karegi.",
                        "Enquiry aate hi welcome"),
                new TAutomation("Site visit reminder", "STAGE_CHANGED", "site_visit", null, "SEND_WHATSAPP",
                        "phone", null, null,
                        "{client_name} ji, aapki site visit {visit_date} ko scheduled hai. Milte hain!",
                        "Site Visit stage par reminder"),
                new TAutomation("No show - reschedule", "STATUS_CHANGED", "site_visit", "No Show",
                        "SEND_WHATSAPP", "phone", null, null,
                        "{client_name} ji, aaj visit nahi ho payi — koi baat nahi! Naya time bata dein, hum arrange kar denge.",
                        "Status 'No Show' par turant re-schedule nudge"),
                new TAutomation("Visit feedback", "STATUS_CHANGED", "site_visit", "Visited",
                        "SEND_WHATSAPP", "phone", null, null,
                        "{client_name} ji, property kaisi lagi? Pasand aayi ho to best offer ke liye batayein!",
                        "Visit ke turant baad feedback + push")))
    );

    private FormTemplateCatalog() {}
}
