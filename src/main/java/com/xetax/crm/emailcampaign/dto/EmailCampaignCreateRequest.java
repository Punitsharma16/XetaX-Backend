package com.xetax.crm.emailcampaign.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Campaign draft. sourceType RECORDS uses formSlug + emailFieldKey + filters;
 * CONTACTS takes every contact with an email; CSV recipients are attached
 * afterwards via the multipart upload endpoint.
 */
@Data
public class EmailCampaignCreateRequest {
    private String name;
    private String subject;
    private String body;
    private String sourceType;
    private String formSlug;
    private String emailFieldKey;
    private String search;
    private Map<String, Object> filters = new HashMap<>();
}
