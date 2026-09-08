package com.xetax.crm.whatsapp.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Campaign draft. sourceType RECORDS uses formSlug + phoneFieldKey + filters;
 * CSV recipients are attached afterwards via the multipart upload endpoint.
 */
@Data
public class CampaignCreateRequest {
    private String name;
    private String sourceType;
    private String messageTemplate;
    private String templateName;
    private String templateLanguage;

    private String formSlug;
    private String phoneFieldKey;
    private String search;
    private Map<String, Object> filters = new HashMap<>();

    /** Payload keys mapped in order to the template's {{1}},{{2}}… variables. */
    private List<String> templateParams = new ArrayList<>();
}
