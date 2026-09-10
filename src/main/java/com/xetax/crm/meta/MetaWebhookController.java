package com.xetax.crm.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Page webhook — lead-form submissions from Facebook and Instagram ads.
 *
 * Deliberately separate from the WhatsApp webhook so the messaging path is
 * untouched; Meta lets each product point at its own callback URL. Same rules
 * as there: verify the signature, answer 200 immediately, do the work off-thread.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/meta/webhook")
@RequiredArgsConstructor
public class MetaWebhookController {

    private final MetaWhatsAppProperties appProperties;
    private final MetaAdsProperties adsProperties;
    private final MetaLeadService leadService;
    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && expectedToken().equals(token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("verification failed");
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload) {
        if (!signatureValid(signature, payload)) {
            log.warn("Meta page webhook rejected: bad or missing signature");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            JsonNode root = mapper.readTree(payload);
            if (!"page".equals(root.path("object").asText())) return ResponseEntity.ok().build();
            for (JsonNode entry : root.path("entry")) {
                String pageId = entry.path("id").asText(null);
                for (JsonNode change : entry.path("changes")) {
                    if (!"leadgen".equals(change.path("field").asText())) continue;
                    JsonNode value = change.path("value");
                    leadService.onLeadgen(
                            value.path("page_id").asText(pageId),
                            value.path("leadgen_id").asText(null),
                            value.path("form_id").asText(null),
                            value.path("ad_id").asText(null),
                            value.path("created_time").asText(null));
                }
            }
        } catch (Exception e) {
            // Never make Meta retry over our own parsing problem.
            log.warn("Meta page webhook could not be read: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    private String expectedToken() {
        String own = adsProperties.getWebhookVerifyToken();
        return own == null || own.isBlank() ? appProperties.getWebhookVerifyToken() : own;
    }

    private boolean signatureValid(String header, String payload) {
        String secret = appProperties.getAppSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("META_APP_SECRET not set — page webhook signature check skipped (dev only)");
            return true;
        }
        if (header == null || !header.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return MessageDigest.isEqual(("sha256=" + hex).getBytes(StandardCharsets.UTF_8),
                    header.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
