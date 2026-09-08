package com.xetax.crm.data_manager.dto.SeacrhDto;

import lombok.Data;
import org.springframework.data.domain.Sort;

import java.util.HashMap;
import java.util.Map;

@Data
public class RecordSearchRequest {

    private int page = 0;

    private int size = 20;

    private String sortBy = "createdAt";

    private Sort.Direction direction = Sort.Direction.DESC;

    // Global Search
    private String search;

    // Dynamic Filters
    private Map<String, Object> filters = new HashMap<>();

}
