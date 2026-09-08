package com.xetax.crm.whatsapp.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WhatsAppTemplateResponse {
    private Long id;
    private String name;
    private String language;
    private String category;
    private String status;
    private String componentsJson;
    private String rejectionReason;
}
