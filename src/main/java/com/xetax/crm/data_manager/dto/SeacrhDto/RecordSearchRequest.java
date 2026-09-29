package com.xetax.crm.data_manager.dto.SeacrhDto;

import lombok.Data;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
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

    /*
     * The record's own columns, which `filters` cannot reach — that map is
     * keyed by form fieldKey and only ever looks inside `data`. All three are
     * optional: left null they add no criteria, so every existing caller
     * searches exactly as before.
     */

    /** Only records sitting in this stage. */
    private Long stageId;

    /** Records created on or after this day (00:00). */
    private LocalDate createdFrom;

    /** Records created on or before this day (23:59:59). */
    private LocalDate createdTo;

}
