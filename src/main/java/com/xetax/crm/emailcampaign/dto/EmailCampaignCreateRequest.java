package com.xetax.crm.emailcampaign.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Campaign draft. sourceType RECORDS uses formSlug + emailFieldKey + filters;
 * CONTACTS takes every contact with an email; CSV recipients are attached
 * afterwards via the multipart upload endpoint.
 *
 * <p>subject and body may be typed straight in, as before. Sending a
 * templateId instead fills them from a saved template; sending both means the
 * typed text wins, so the panel can load a template and let it be edited.
 */
@Data
public class EmailCampaignCreateRequest {
    private String name;
    private String subject;
    private String body;
    private Long templateId;
    private String sourceType;
    private String formSlug;
    private String emailFieldKey;
    private String search;
    private Map<String, Object> filters = new HashMap<>();
}
