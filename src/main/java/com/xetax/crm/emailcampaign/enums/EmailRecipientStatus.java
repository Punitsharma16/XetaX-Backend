package com.xetax.crm.emailcampaign.enums;

/**
 * SMTP gives no delivery/read receipts, so unlike WhatsApp a recipient ends
 * at SENT (accepted by the org's mail server) or FAILED.
 */
public enum EmailRecipientStatus {
    PENDING, QUEUED, SENT, FAILED
}
