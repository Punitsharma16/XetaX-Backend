package com.xetax.crm.billing;

import com.xetax.crm.common.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Thin Razorpay Orders client — deliberately SDK-free (two REST calls and an
 * HMAC don't justify a jar, and new jars need a full JVM restart here).
 *
 * Setup: RAZORPAY_KEY_ID + RAZORPAY_KEY_SECRET in crm/.env
 * (dashboard.razorpay.com → Account & Settings → API Keys; use rzp_test_*
 * keys first). Without them, top-ups show as "payment not configured".
 */
@Component
@Slf4j
public class RazorpayClient {

    @Value("${razorpay.key-id:}")
    private String keyId;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    private final RestClient http = RestClient.builder()
            .baseUrl("https://api.razorpay.com/v1").build();

    public boolean configured() {
        return keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank();
    }

    public String keyId() {
        return keyId;
    }

    /** Creates an order and returns its id (order_xxx). Amount is in paise. */
    @SuppressWarnings("unchecked")
    public String createOrder(int amountPaise, String receipt) {
        if (!configured()) throw new BadRequestException("Payment is not configured yet");
        try {
            Map<String, Object> res = http.post().uri("/orders")
                    .header("Authorization", basicAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", amountPaise, "currency", "INR", "receipt", receipt))
                    .retrieve()
                    .body(Map.class);
            String id = res == null ? null : (String) res.get("id");
            if (id == null) throw new IllegalStateException("No order id in Razorpay response");
            return id;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.error("Razorpay order create failed: {}", e.getMessage());
            throw new BadRequestException("Could not start the payment — try again in a minute");
        }
    }

    /** Standard checkout verification: HMAC-SHA256(orderId|paymentId, secret). */
    public boolean verifySignature(String orderId, String paymentId, String signature) {
        if (!configured() || orderId == null || paymentId == null || signature == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            // Constant-time compare — a timing oracle on a payment check is a gift.
            return java.security.MessageDigest.isEqual(
                    hex.toString().getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Signature verification errored: {}", e.getMessage());
            return false;
        }
    }

    private String basicAuth() {
        return "Basic " + Base64.getEncoder()
                .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    }
}
