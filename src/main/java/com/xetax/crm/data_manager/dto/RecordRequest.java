package com.xetax.crm.data_manager.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Map;

@Getter
@Setter
@ToString
public class RecordRequest {

    private Map<String, Object> data;

}
