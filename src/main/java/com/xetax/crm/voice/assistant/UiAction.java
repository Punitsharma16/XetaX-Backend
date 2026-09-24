package com.xetax.crm.voice.assistant;

/**
 * What the app should do on screen, alongside what the assistant says.
 *
 * <p>Deliberately not free-form: the model cannot hand the app a route, a URL
 * or anything else to execute. It picks one screen from a closed list and may
 * pass an id or a search term, and the app decides what that means. The worst
 * a confused model can do is open the wrong page.
 *
 * @param screen where to go
 * @param id     the record/contact/invoice the screen is about, when it needs one
 * @param query  a search or filter term the screen should apply
 * @param label  what the app may show while it happens ("Opening Ravi Sharma")
 */
public record UiAction(Screen screen, String id, String query, String label) {

    /**
     * Every destination the assistant is allowed to name, and the route the
     * mobile app resolves it to. Adding a screen here is the only way to make
     * a new one reachable by voice.
     */
    public enum Screen {
        HOME("/(tabs)"),
        TASKS("/(tabs)/tasks"),
        RECORDS("/(tabs)/records"),
        INBOX("/(tabs)/inbox"),
        CONTACTS("/contacts"),
        RECORD_DETAILS("/record/[id]"),
        RECORD_LIST("/records/[slug]"),
        NEW_TASK("/task/new"),
        INVOICES("/invoices"),
        INVOICE_DETAILS("/invoice/[id]"),
        CHAT("/chat/[id]"),
        NOTIFICATIONS("/notifications"),
        EMAIL_CAMPAIGNS("/email-campaigns"),
        FLOWS("/flows"),
        TEMPLATES("/templates"),
        TEAM("/team"),
        PROFILE("/profile");

        private final String route;

        Screen(String route) {
            this.route = route;
        }

        public String route() {
            return route;
        }
    }
}
