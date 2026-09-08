package com.xetax.crm.integration.controller;

import com.xetax.crm.integration.service.IntegrationIngestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/public/integrations")
@RequiredArgsConstructor
public class PublicIntegrationController {

    private final IntegrationIngestService integrationIngestService;

    private final com.xetax.crm.common.ratelimit.RateLimiterService rateLimiter;

    @PostMapping("/{integrationKey}")
    public ResponseEntity<Void> receive(
            @PathVariable String integrationKey,
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody @Valid Map<String, Object> payload) {

        // 120 payloads per key per minute — one misbehaving source cannot
        // flood the CRM.
        rateLimiter.check("wh:" + integrationKey, 120, java.time.Duration.ofMinutes(1));

        integrationIngestService.ingest(
                integrationKey,
                apiKey,
                payload
        );

        return ResponseEntity.ok().build();
    }
}
