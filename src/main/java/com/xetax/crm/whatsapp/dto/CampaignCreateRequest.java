package com.xetax.crm.whatsapp.dto;

import lombok.Data;

import java.time.LocalDate;
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

    /*
     * Narrowing for a RECORDS audience, all optional. `filters` is keyed by
     * form fieldKey and only reaches inside a record's data, so a stage and
     * the record's own created date need their own fields.
     */

    /** Only records sitting in this stage of the form. */
    private Long stageId;

    /** Records created on or after this day. */
    private LocalDate createdFrom;

    /** Records created on or before this day. */
    private LocalDate createdTo;

    /** Payload keys mapped in order to the template's {{1}},{{2}}… variables. */
    private List<String> templateParams = new ArrayList<>();

    /**
     * Field keys (RECORDS) or CSV column names for every variable of the
     * template, in its shape. Supersedes templateParams, which only covered the body.
     * A header / card media link here is used as-is, not looked up.
     */
    private TemplateVariables templateVariables;
}
