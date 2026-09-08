package com.xetax.crm.data_manager.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecordResponse {

    private String id;

    private Long formId;

    private Long stageId;


    private String assignedTo;

    private Map<String, Object> data;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
