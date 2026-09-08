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
    /** JSON array of up to 3 quick-reply button labels — makes the send INTERACTIVE. */
    private String buttonsJson;
}
