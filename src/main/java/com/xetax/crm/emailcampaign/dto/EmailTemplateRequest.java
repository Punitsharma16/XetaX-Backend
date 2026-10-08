package com.xetax.crm.emailcampaign.dto;

import lombok.Data;

/** What the panel sends when saving or editing a template. */
@Data
public class EmailTemplateRequest {
    private String name;
    private String subject;
    private String body;
}
