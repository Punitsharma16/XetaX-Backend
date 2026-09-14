package com.xetax.crm.whatsapp.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** A Flow to create or replace: its name, what it is for, and its screens. */
@Data
public class FlowCreateRequest {
    private String name;
    /** SIGN_UP | SIGN_IN | APPOINTMENT_BOOKING | LEAD_GENERATION | CONTACT_US | CUSTOMER_SUPPORT | SURVEY | OTHER */
    private List<String> categories;
    /** The Flow JSON. Left empty, a lead-capture Flow is generated from the form. */
    private String flowJson;
    /** Answers land in this CRM form when set. */
    private Long formId;
    /** Flow field name → CRM field key. */
    private Map<String, String> fieldMap;
    /** Publish straight away instead of leaving it as a draft. */
    private boolean publish;
}
