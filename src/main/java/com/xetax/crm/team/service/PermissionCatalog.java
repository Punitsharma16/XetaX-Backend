package com.xetax.crm.team.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The fixed set of permission keys the whole app is gated by. Roles pick any
 * subset. Adding a feature = add its key here + annotate its endpoints; the
 * role editor UI renders straight from this catalog.
 */
public final class PermissionCatalog {

    public record Permission(String key, String label) {}

    /** group label -> permissions, in display order. */
    public static final Map<String, List<Permission>> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("Forms & Pipelines", List.of(
                new Permission("forms.view", "View forms"),
                new Permission("forms.manage", "Create, edit and delete forms, fields and stages")));
        GROUPS.put("Records", List.of(
                new Permission("records.view", "View and search all records"),
                new Permission("records.view.own", "View only records assigned to them"),
                new Permission("records.create", "Create records and import CSV"),
                new Permission("records.edit", "Edit records and change stage/status"),
                new Permission("records.delete", "Delete records"),
                new Permission("records.transfer", "Transfer records to another member"),
                new Permission("records.unlock", "Unlock final-stage records (revert approved/closed)")));
        GROUPS.put("Documents", List.of(
                new Permission("documents.view", "View and send documents"),
                new Permission("documents.manage", "Upload and delete documents")));
        GROUPS.put("Invoices", List.of(
                new Permission("invoices.view", "View invoices and download PDFs"),
                new Permission("invoices.manage", "Create, send and record payments on invoices")));
        GROUPS.put("Contacts", List.of(
                new Permission("contacts.view", "View contacts and email history"),
                new Permission("contacts.manage", "Manage contacts and send WhatsApp/email")));
        GROUPS.put("Automations", List.of(
                new Permission("automations.view", "Automations dekhna"),
                new Permission("automations.manage", "Automations banana-badalna")));
        GROUPS.put("Integrations", List.of(
                new Permission("integrations.view", "Integrations dekhna"),
                new Permission("integrations.manage", "Integrations banana-badalna")));
        GROUPS.put("WhatsApp", List.of(
                new Permission("whatsapp.view", "WhatsApp setup/usage dekhna"),
                new Permission("whatsapp.manage", "Connect/disconnect & templates"),
                new Permission("whatsapp.inbox", "Inbox & messages bhejna"),
                new Permission("whatsapp.campaigns", "Campaigns banana & chalana")));
        GROUPS.put("Meetings", List.of(
                new Permission("meetings.view", "Meetings dekhna & join"),
                new Permission("meetings.manage", "Meetings banana, share, notes")));
        GROUPS.put("AI Assistant", List.of(
                new Permission("ai.use", "AI assistant use karna"),
                new Permission("agents.manage", "Public AI agents banana & unka knowledge manage")));
        GROUPS.put("Live Chat Desk", List.of(
                new Permission("desk.handle", "Handoff requests dekhna & customers se live chat")));
        GROUPS.put("Team", List.of(
                new Permission("team.manage", "Members & roles manage karna")));
    }

    public static final Set<String> ALL_KEYS = GROUPS.values().stream()
            .flatMap(List::stream).map(Permission::key)
            .collect(Collectors.toUnmodifiableSet());

    private PermissionCatalog() {}
}
