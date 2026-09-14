package com.xetax.crm.whatsapp.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A Flow as the panel lists it. */
@Data
@Builder
public class FlowView {
    private Long id;
    private String metaFlowId;
    private String name;
    private String status;
    private String category;
    private String flowJson;
    private Long formId;
    private Map<String, String> fieldMap;
    private Instant publishedAt;
    private String lastError;
    private List<String> validationErrors;
    private long responseCount;
}
