package com.xetax.crm.data_manager.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FormRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String slug;

    private String description;

    private String icon;

    private String color;

}
