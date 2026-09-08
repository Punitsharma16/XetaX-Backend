package com.xetax.crm.whatsapp.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CampaignRecipientResponse {
    private Long id;
    private String phone;
    private String recordId;
    private String status;
    private int attemptCount;
    private String error;
}
