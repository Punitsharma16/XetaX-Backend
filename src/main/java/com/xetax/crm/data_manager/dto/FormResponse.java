package com.xetax.crm.data_manager.dto;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FormResponse {

    private Long id;

    private String name;

    private String slug;

    private String description;

    private String icon;

    private String color;

}
