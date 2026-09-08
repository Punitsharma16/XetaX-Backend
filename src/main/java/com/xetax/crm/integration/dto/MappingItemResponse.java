package com.xetax.crm.integration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MappingItemResponse {

    private Long id;

    /** External key the webhook payload must send (stored lowercased). */
    private String sourceField;

    private Long formFieldId;

    private String fieldKey;

    private String fieldLabel;

}
