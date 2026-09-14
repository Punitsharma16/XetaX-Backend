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

    /* ---- request-shape problems the framework raises: they are the caller's
       fault, so answer 4xx with a plain message instead of a 500. ---- */

    @ExceptionHandler({
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.bind.ServletRequestBindingException.class
    })
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception ex) {
        String message = switch (ex) {
            case org.springframework.web.bind.MissingServletRequestParameterException e ->
                    "Missing request parameter '" + e.getParameterName() + "'";
            case org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e ->
                    "Parameter '" + e.getName() + "' has an invalid value";
            case org.springframework.web.multipart.support.MissingServletRequestPartException e ->
                    "Missing file part '" + e.getRequestPartName() + "'";
            case org.springframework.http.converter.HttpMessageNotReadableException e ->
                    "Request body is not valid JSON";
            default -> "Malformed request";
        };
        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .success(false).status(HttpStatus.BAD_REQUEST.value()).error("BAD_REQUEST").message(message).build());
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(org.springframework.web.HttpMediaTypeNotAcceptableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(ErrorResponse.builder()
                .success(false).status(HttpStatus.NOT_ACCEPTABLE.value()).error("NOT_ACCEPTABLE")
                .message("This endpoint does not produce the requested content type").build());
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(org.springframework.web.HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ErrorResponse.builder()
                .success(false).status(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()).error("UNSUPPORTED_MEDIA_TYPE")
                .message("Unsupported request content type").build());
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ErrorResponse.builder()
                .success(false).status(HttpStatus.METHOD_NOT_ALLOWED.value()).error("METHOD_NOT_ALLOWED")
                .message("Method " + ex.getMethod() + " is not allowed here").build());
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse.builder()
                .success(false).status(HttpStatus.PAYLOAD_TOO_LARGE.value()).error("PAYLOAD_TOO_LARGE")
                .message("The uploaded file is too large").build());
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(org.springframework.dao.DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.builder()
                .success(false).status(HttpStatus.CONFLICT.value()).error("CONFLICT")
                .message("This change conflicts with existing data — something still refers to it").build());
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
