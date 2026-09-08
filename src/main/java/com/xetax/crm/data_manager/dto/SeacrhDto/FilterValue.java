package com.xetax.crm.data_manager.dto.SeacrhDto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;

@Data
public class FilterValue {

    private Object value;

    private Double min;

    private Double max;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate from;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate to;

}