package com.xetax.crm.auth.exception;

import com.xetax.crm.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Auth-specific error mapping, scoped to the auth controllers so the CRM's
 * GlobalExceptionHandler (whose catch-all would turn a bad login into a 500)
 * never sees these. Mirrors the standalone auth service: authentication
 * failures and duplicate-email answers are 400s with a readable message.
 */
@RestControllerAdvice(basePackages = "com.xetax.crm.auth")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {
    private final Logger logger = LoggerFactory.getLogger(AuthExceptionHandler.class);

    @ExceptionHandler({UsernameNotFoundException.class, BadCredentialsException.class,
            CredentialsExpiredException.class, AuthenticationException.class, DisabledException.class})
    public ResponseEntity<ErrorResponse> handleAuthException(Exception e, HttpServletRequest request) {
        logger.info("Exception :{}", e.getClass().getName());
        return ResponseEntity.badRequest().body(
                ErrorResponse.builder()
                        .success(false)
                        .status(HttpStatus.BAD_REQUEST.value())
                        .error("BAD_REQUEST")
                        .message(e.getMessage())
                        .build()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(
                ErrorResponse.builder()
                        .success(false)
                        .status(HttpStatus.BAD_REQUEST.value())
                        .error("BAD_REQUEST")
                        .message(e.getMessage())
                        .build()
        );
    }
}
