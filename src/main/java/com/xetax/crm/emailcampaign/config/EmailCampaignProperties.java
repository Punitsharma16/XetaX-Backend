package com.xetax.crm.emailcampaign.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bulk-email pacing. Both values come from configuration (application.yaml
 * placeholders → EMAIL_CAMPAIGN_* env vars) so an operator can raise them
 * without a code change. Defaults mirror the WhatsApp campaign limits.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "xetax.email.campaign")
public class EmailCampaignProperties {

    /** Hard cap on recipients per campaign — bigger lists are split by the user. */
    private int maxRecipients = 5000;

    /**
     * Per-owner outbound emails per minute. Mail providers throttle bursts
     * (Gmail, Zoho, Outlook all do); pacing keeps a big blast from tripping
     * the org's own SMTP account into a temporary block.
     */
    private int sendsPerMinute = 60;
}
