package com.xetax.crm.emailcampaign.enums;

/**
 * Where the audience comes from: records of one form (an email field),
 * every contact that has an email address, or an uploaded CSV.
 */
public enum EmailCampaignSourceType {
    RECORDS, CONTACTS, CSV
}
