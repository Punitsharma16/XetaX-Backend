package com.xetax.crm.emailcampaign.enums;

/** Same lifecycle as a WhatsApp campaign so the panel can share its badges. */
public enum EmailCampaignStatus {
    DRAFT, SCHEDULED, QUEUED, RUNNING, PAUSED, COMPLETED, PARTIAL, FAILED, CANCELLED
}
