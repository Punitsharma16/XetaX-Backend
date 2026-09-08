package com.xetax.crm.data_manager.dto;

import com.xetax.crm.data_manager.enums.StageStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/*
 * Lombok annotations were missing, so the class had no accessors: MapStruct
 * could not populate it and Jackson serialised every stage as "{}". Annotated
 * to match the other response DTOs in this package.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StageResponse {
    private Long id;

    private Long formId;

    private String name;

    private String code;

    private String color;

    private Integer sequence;

    private Boolean isDefault;

    private Boolean isFinal;

    private StageStatus status;
}
