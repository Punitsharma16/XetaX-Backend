package com.xetax.crm.whatsapp.dto;

import lombok.Data;

/**
 * One outbound send. Either a free-text body (24h window) or a template.
 * Target = raw phone, an existing conversation, or a record + phone field.
 */
@Data
public class SendMessageRequest {
    private String phone;
    private Long conversationId;
    private String recordId;
    private String phoneFieldKey;
    private String message;
    private String templateName;
    private String templateLanguage;
    private String componentsJson;
    /**
     * Values for the template's variables. When componentsJson is not given,
     * the send builds it from these and the synced template, checking that
     * every variable has exactly one value.
     */
    private TemplateVariables templateVariables;
    /** JSON array of up to 3 quick-reply button labels — makes the send INTERACTIVE. */
    private String buttonsJson;
}
