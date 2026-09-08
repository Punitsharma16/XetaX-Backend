package com.xetax.crm.data_manager.dto;

import com.xetax.crm.data_manager.enums.StageStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StageRequest {
    @NotBlank(message = "Stage name is required")
    private String name;

    @NotBlank(message = "Stage code is required")
    private String code;

    private String color;

    @NotNull(message = "Sequence is required")
    private Integer sequence;

    private Boolean isDefault = false;

    private Boolean isFinal = false;

    @NotNull(message = "Stage status is required")
    private StageStatus status;
}
