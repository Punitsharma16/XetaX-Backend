package com.xetax.crm.common;

/**
 * Whether a configuration value was really supplied.
 *
 * <p>An environment variable nobody set arrives blank, and everything
 * downstream handles blank properly. A half-finished setup is worse: it leaves
 * the placeholder from the deployment notes behind, and a placeholder is not
 * blank, so every "is this configured?" check says yes. Production was running
 * with {@code META_LEADS_CONFIG_ID} set to the literal text
 * {@code <naya configuration id>}: the panel believed Facebook Ads was ready,
 * drew a live Connect button, and handed that text to Facebook as the config
 * id — so the customer got an error from Facebook with nothing pointing at the
 * real cause. The panel already had the right message for an unset value; it
 * simply never got to show it.
 */
public final class ConfigValues {

    private ConfigValues() {
    }

    /** True when the value is present and is not a {@code <placeholder>}. */
    public static boolean isSet(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return false;
        return !(trimmed.startsWith("<") && trimmed.endsWith(">"));
    }

    /** The value when it is really set, otherwise "" — never a placeholder. */
    public static String orEmpty(String value) {
        return isSet(value) ? value.trim() : "";
    }
}
