package com.xetax.crm.meta;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lead-ads specific settings. The app id / secret / Graph version are shared
 * with the WhatsApp integration (it is the same Meta app), so only what is
 * different lives here.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "meta.ads")
public class MetaAdsProperties {

    /**
     * Facebook Login for Business configuration id — a SECOND configuration in
     * the same app, asking for the page/leads/ads permissions instead of the
     * WhatsApp ones. Empty = the Facebook card stays hidden in the panel.
     */
    private String configId = "";

    /** Webhook verify token for the page webhook; falls back to the WhatsApp one. */
    private String webhookVerifyToken = "";

    /** How many days of spend to re-pull on every sync (Meta restates recent days). */
    private int insightsLookbackDays = 7;
}
