package com.xetax.crm.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex){

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                        ErrorResponse.builder()
                                .success(false)
                                .status(HttpStatus.NOT_FOUND.value())
                                .error("NOT_FOUND")
                                .message(ex.getMessage())
                                .build()
                );
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex){

        return ResponseEntity.badRequest()
                .body(
                        ErrorResponse.builder()
                                .success(false)
                                .status(HttpStatus.BAD_REQUEST.value())
                                .error("BAD_REQUEST")
                                .message(ex.getMessage())
                                .build()
                );
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex){

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(
                        ErrorResponse.builder()
                                .success(false)
                                .status(HttpStatus.UNAUTHORIZED.value())
                                .error("UNAUTHORIZED")
                                .message(ex.getMessage())
                                .build()
                );
    }

    @ExceptionHandler(com.xetax.crm.common.ratelimit.RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(com.xetax.crm.common.ratelimit.RateLimitExceededException ex){

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(
                        ErrorResponse.builder()
                                .success(false)
                                .status(HttpStatus.TOO_MANY_REQUESTS.value())
                                .error("TOO_MANY_REQUESTS")
                                .message(ex.getMessage())
                                .build()
                );
    }

    /** Permission gate (403) and other explicit-status throws keep their status. */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex) {

        return ResponseEntity.status(ex.getStatusCode())
                .body(
                        ErrorResponse.builder()
                                .success(false)
                                .status(ex.getStatusCode().value())
                                .error(ex.getStatusCode().toString())
                                .message(ex.getReason())
                                .build()
                );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex){

        return ResponseEntity.internalServerError()
                .body(
                        ErrorResponse.builder()
                                .success(false)
                                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                                .error("INTERNAL_SERVER_ERROR")
                                .message(ex.getMessage())
                                .build()
                );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex){

        List<String> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(field -> field.getField() + " : " + field.getDefaultMessage())
                .toList();

        return ResponseEntity.badRequest()
                .body(
                        ErrorResponse.builder()
                                .success(false)
                                .status(HttpStatus.BAD_REQUEST.value())
                                .error("VALIDATION_ERROR")
                                .message("Validation Failed")
                                .details(errors)
                                .build()
                );
    }

}
