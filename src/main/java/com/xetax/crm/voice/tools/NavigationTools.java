package com.xetax.crm.voice.tools;

import com.xetax.crm.voice.assistant.UiAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * The one tool that reaches the screen instead of the database.
 *
 * <p>Making navigation a tool rather than a field the model fills in has two
 * benefits: it travels the same validated path as every other capability, and
 * the model chooses it the same way it chooses to look a contact up — so
 * "Ravi ko dikhao" naturally becomes a search followed by an open.
 *
 * <p>Nothing is executed here. The choice is parked for the turn and the
 * socket sends it to the app once the model has finished talking.
 */
@Component
@Slf4j
public class NavigationTools {

    /**
     * One turn runs on one thread, and the model may call this mid-conversation
     * while the socket waits — so the pick is held per thread and read back by
     * the same thread that started the turn.
     */
    private static final ThreadLocal<UiAction> PENDING = new ThreadLocal<>();

    @Tool(description = """
            Open a screen in the user's mobile app. Call this whenever the user asks to
            SEE, SHOW, OPEN or GO TO something ("Ravi ko dikhao", "show my tasks",
            "aaj ki meetings dikhao"), in addition to answering out loud.
            Look the thing up first with a read tool so you can pass its real id.
            Do not call this when the user only asked a question.""")
    public String openScreen(
            @ToolParam(description = """
                    One of: HOME, TASKS, RECORDS, INBOX, CONTACTS, RECORD_DETAILS,
                    RECORD_LIST, NEW_TASK, INVOICES, INVOICE_DETAILS, CHAT,
                    NOTIFICATIONS, EMAIL_CAMPAIGNS, FLOWS, TEMPLATES, TEAM, PROFILE""")
            String screen,
            @ToolParam(required = false, description =
                    "The id of the record, invoice or chat the screen is about. Omit for list screens.")
            String id,
            @ToolParam(required = false, description =
                    "A search or filter term the screen should open with, e.g. a name or 'today'.")
            String query) {

        UiAction.Screen target;
        try {
            target = UiAction.Screen.valueOf(screen.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            // Told, not thrown: the model can correct itself on the next step.
            return "There is no screen called '" + screen + "'. Pick one from the listed names.";
        }

        PENDING.set(new UiAction(target, blankToNull(id), blankToNull(query), null));
        log.debug("Voice UI action queued: {} id={} query={}", target, id, query);
        return "Opening " + target.name() + " on the user's screen.";
    }

    /** Takes the turn's action, leaving nothing behind for the next one. */
    public UiAction takePending() {
        UiAction action = PENDING.get();
        PENDING.remove();
        return action;
    }

    /** Always call before a turn — a thread from the pool may carry a stale pick. */
    public void clear() {
        PENDING.remove();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
