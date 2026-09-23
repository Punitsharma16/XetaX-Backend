package com.xetax.crm.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.xetax.crm.common.exception.ErrorResponse;
import com.xetax.crm.common.exception.GlobalExceptionHandler;

/**
 * A URL nobody serves is the caller's mistake. It used to come back 500 with
 * Spring's own wording, so every typo'd path and every scanner probing the
 * host looked to a monitor like the API falling over.
 */
class UnknownRouteStatusTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("an unknown path is 404, not 500")
    void unknownPathIsNotFound() {
        ResponseEntity<ErrorResponse> response = handler.handleNoRoute(
                new NoResourceFoundException(org.springframework.http.HttpMethod.GET,
                        "/api/whatsapp/status", "api/whatsapp/status"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("the answer says nothing about how the server is built")
    void doesNotLeakInternals() {
        ResponseEntity<ErrorResponse> response = handler.handleNoRoute(
                new NoResourceFoundException(org.springframework.http.HttpMethod.GET,
                        "/api/does-not-exist", "api/does-not-exist"));

        String message = response.getBody().getMessage();
        assertThat(message).isEqualTo("No such endpoint");
        assertThat(message).doesNotContain("static resource").doesNotContain("/api/");
    }
}
