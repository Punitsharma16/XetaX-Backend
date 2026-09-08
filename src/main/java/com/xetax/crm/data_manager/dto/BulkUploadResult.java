package com.xetax.crm.data_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** Outcome of a CSV bulk upload: per-row errors, nothing partial hidden. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkUploadResult {

    private int totalRows;

    private int successCount;

    private int failedCount;

    @Builder.Default
    private List<RowError> errors = new ArrayList<>();

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RowError {
        /** 1-based data-row number (header row excluded). */
        private int row;
        private String message;
    }
}
