package com.xetax.crm.integration.controller;

import com.xetax.crm.integration.service.IntegrationIngestService;
import com.xetax.crm.integration.service.MappedPayload;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/public/integrations")
@RequiredArgsConstructor
public class PublicIntegrationController {

    private final IntegrationIngestService integrationIngestService;

    private final com.xetax.crm.common.ratelimit.RateLimiterService rateLimiter;

    @PostMapping("/{integrationKey}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String integrationKey,
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody @Valid Map<String, Object> payload) {

        // 120 payloads per key per minute — one misbehaving source cannot
        // flood the CRM.
        rateLimiter.check("wh:" + integrationKey, 120, java.time.Duration.ofMinutes(1));

        MappedPayload result = integrationIngestService.ingest(
                integrationKey,
                apiKey,
                payload
        );

        // The reply used to be an empty 200, which hid the commonest webhook
        // mistake: a key with no mapping is dropped, and the sender had no way
        // to tell. Only key names go back — never a stored value, and never the
        // payload itself.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("mappedFields", result.data().size());
        body.put("ignoredKeys", result.ignoredPaths());
        body.put("missingMappedFields", result.unmatchedFields());

        return ResponseEntity.ok(body);
    }
}
