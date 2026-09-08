package com.xetax.crm.common.exception;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    private boolean success;

    private int status;

    private String error;

    private String message;

    private List<String> details;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

}
