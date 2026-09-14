package com.xetax.crm.whatsapp.dto;

import lombok.Data;

/** Sends a published Flow to one customer. */
@Data
public class FlowSendRequest {
    private Long flowId;
    private String phone;
    private Long conversationId;
    private String recordId;
    private String headerText;
    private String bodyText;
    private String footerText;
    /** The label on the button that opens the Flow. */
    private String ctaText;
}
