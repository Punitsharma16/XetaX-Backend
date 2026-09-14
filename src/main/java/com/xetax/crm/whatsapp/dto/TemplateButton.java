package com.xetax.crm.whatsapp.dto;

import lombok.Data;

/**
 * One button under a template.
 *
 * <p>Meta's rules, enforced in WhatsAppTemplateService: at most 10 buttons,
 * of which at most 2 may be URL and at most 1 PHONE_NUMBER. A URL may carry a
 * single trailing {{1}} variable, and then needs an example so reviewers can
 * see where it leads.
 */
@Data
public class TemplateButton {
    /** URL | PHONE_NUMBER | QUICK_REPLY | FLOW */
    private String type;
    /** Button label as the customer sees it (Meta caps this at 25 characters). */
    private String text;
    /** URL buttons only. */
    private String url;
    /** Sample value for a URL that ends in {{1}}. */
    private String urlExample;
    /** PHONE_NUMBER buttons only, in full international form. */
    private String phoneNumber;
    /** FLOW buttons only — the published Flow this button opens. */
    private String flowId;
    /** FLOW buttons only: NAVIGATE (default) or DATA_EXCHANGE. */
    private String flowAction;
}
