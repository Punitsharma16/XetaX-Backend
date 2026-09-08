package com.xetax.crm.whatsapp.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import com.xetax.crm.whatsapp.kafka.WhatsAppEventPublisher;
import com.xetax.crm.whatsapp.webhook.WhatsAppWebhookProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Public Meta webhook. GET = one-time verification handshake, POST = events.
 * POST verifies the X-Hub-Signature-256 HMAC before trusting anything, then
 * pushes each event onto Kafka (DLR/inbound topic) and returns 200 fast.
 * If the broker is down the event is processed inline instead — never lost.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/whatsapp/webhook")
@RequiredArgsConstructor
public class WhatsAppWebhookController {

    private final MetaWhatsAppProperties properties;
    private final WhatsAppEventPublisher eventPublisher;
    private final WhatsAppWebhookProcessor processor;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && properties.getWebhookVerifyToken().equals(token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("verification failed");
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload) {

        MDC.put("correlationId", java.util.UUID.randomUUID().toString().substring(0, 8));
        try {
        if (!signatureValid(signature, payload)) {
            log.warn("WhatsApp webhook rejected: bad or missing signature");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    String field = change.path("field").asText();
                    if ("message_template_status_update".equals(field)) {
                        // Rare, tiny events — applied inline (entry.id is the WABA id).
                        processor.processTemplateStatusRaw(
                                entry.path("id").asText(null),
                                change.path("value").toString());
                        continue;
                    }
                    if (!"messages".equals(field)) {
                        continue; // account updates etc. — not handled
                    }
                    JsonNode value = change.path("value");
                    String phoneNumberId = value.path("metadata").path("phone_number_id").asText(null);
                    boolean queued = eventPublisher.publishWebhookEvent(phoneNumberId, value.toString());
                    if (!queued) {
                        processor.processRawValue(value.toString());
                    }
                }
            }
        } catch (Exception e) {
            // Always 200 — Meta retries on non-2xx and a poison payload would
            // otherwise be redelivered forever.
            log.error("WhatsApp webhook handling error: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
        } finally {
            MDC.remove("correlationId");
        }
    }

    private boolean signatureValid(String header, String payload) {
        String secret = properties.getAppSecret();
        if (secret == null || secret.isBlank()) {
            // Dev mode without an app secret: accept, but say so loudly.
            log.warn("META_APP_SECRET not set — webhook signature check skipped (dev only)");
            return true;
        }
        if (header == null || !header.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            String expected = "sha256=" + hex;
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    header.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
