package com.xetax.crm.emailcampaign.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class EmailCampaignRecipientResponse {
    private Long id;
    private String email;
    private String recordId;
    private Long contactId;
    private String status;
    private int attemptCount;
    private String error;
}
