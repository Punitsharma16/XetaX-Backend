package com.xetax.crm.whatsapp.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/** One submitted Flow, as the panel shows it. */
@Data
@Builder
public class FlowResponseView {
    private Long id;
    private Long flowId;
    private String flowName;
    private String customerPhone;
    private Long conversationId;
    private Map<String, Object> answers;
    private String recordId;
    private String note;
    private LocalDateTime submittedAt;
}
