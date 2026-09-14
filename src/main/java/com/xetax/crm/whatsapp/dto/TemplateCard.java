package com.xetax.crm.whatsapp.dto;

import lombok.Data;

import java.util.List;

/**
 * One card of a carousel template.
 *
 * <p>Meta requires every card to have the same shape: same header format and
 * the same number and type of buttons. WhatsAppTemplateService checks that
 * before submitting, because Meta's own error for a mismatch is unreadable.
 */
@Data
public class TemplateCard {
    /** IMAGE (default) or VIDEO — every card must use the same one. */
    private String headerFormat;
    /** Sample media handle for this card, from the sample upload endpoint. */
    private String headerHandle;
    /** The media this card shows on every send. */
    private String headerMediaUrl;
    private String bodyText;
    private List<String> exampleParams;
    private List<TemplateButton> buttons;
}
