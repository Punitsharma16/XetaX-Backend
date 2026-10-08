package com.xetax.crm.emailcampaign.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class EmailTemplateResponse {
    private Long id;
    private String name;
    private String subject;
    private String body;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
