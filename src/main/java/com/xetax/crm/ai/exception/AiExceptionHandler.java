package com.xetax.crm.ai.exception;

import com.xetax.crm.common.exception.ErrorResponse;
import com.xetax.crm.common.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error mapping for the AI endpoints only.
 *
 * <p>LLM-provider exceptions carry sensitive detail in their message —
 * organization ids, quota numbers, endpoint URLs — and the global catch-all
 * used to pass {@code ex.getMessage()} straight to the client. Here the full
 * cause is logged server-side and the client only ever sees a generic,
 * safe message.
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.xetax.crm.ai")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiExceptionHandler {

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.builder()
                        .success(false)
                        .status(HttpStatus.UNAUTHORIZED.value())
                        .error("UNAUTHORIZED")
                        .message(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(com.xetax.crm.common.ratelimit.RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(com.xetax.crm.common.ratelimit.RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.builder()
                        .success(false)
                        .status(HttpStatus.TOO_MANY_REQUESTS.value())
                        .error("TOO_MANY_REQUESTS")
                        .message(ex.getMessage())
                        .build());
    }

    /**
     * Quota refusals are OUR message, written for the user — unlike provider
     * errors they must reach the client verbatim, as a 400 not a 503.
     */
    @ExceptionHandler(com.xetax.crm.common.exception.BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(
            com.xetax.crm.common.exception.BadRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .success(false)
                        .status(HttpStatus.BAD_REQUEST.value())
                        .error("BAD_REQUEST")
                        .message(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAiFailure(Exception ex) {
        // Full detail stays in the server log only.
        log.error("AI request failed: {}", ex.getMessage());

        String message = isRateLimited(ex)
                ? "The AI assistant is busy right now. Please try again in a few minutes."
                : "The AI assistant is temporarily unavailable. Please try again later.";

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.builder()
                        .success(false)
                        .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                        .error("AI_UNAVAILABLE")
                        .message(message)
                        .build());
    }

    private boolean isRateLimited(Exception ex) {
        String msg = ex.getMessage();
        return msg != null && (msg.contains("429") || msg.toLowerCase().contains("rate limit"));
    }
}
