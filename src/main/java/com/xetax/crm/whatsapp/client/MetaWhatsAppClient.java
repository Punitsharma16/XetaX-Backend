package com.xetax.crm.whatsapp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over the Meta Graph API (Cloud API). Every URL is built from
 * MetaWhatsAppProperties — the version is NEVER hardcoded. Tokens are passed
 * per call and never logged; error bodies are logged without auth headers.
 */
@Slf4j
@Component
public class MetaWhatsAppClient {

    private final MetaWhatsAppProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public MetaWhatsAppClient(MetaWhatsAppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getApiConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getApiReadTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /* ---------------------------------------------------- onboarding calls */

    /** Embedded Signup: exchange the FB.login code for a business access token. */
    public String exchangeCodeForToken(String code) {
        JsonNode node = get(properties.apiUrl("/oauth/access_token")
                + "?client_id=" + properties.getAppId()
                + "&client_secret=" + properties.getAppSecret()
                + "&code=" + code, null);
        String token = node.path("access_token").asText(null);
        if (token == null) {
            throw new WhatsAppProviderException("NO_TOKEN",
                    "Meta did not return an access token. Please retry the signup flow.",
                    "oauth/access_token response had no access_token field");
        }
        return token;
    }

    /** Reads WABA ids granted to the token via /debug_token granular scopes. */
    public List<String> findGrantedWabaIds(String businessToken) {
        String appToken = properties.getAppId() + "|" + properties.getAppSecret();
        JsonNode node = get(properties.apiUrl("/debug_token")
                + "?input_token=" + businessToken, appToken);
        List<String> ids = new ArrayList<>();
        for (JsonNode scope : node.path("data").path("granular_scopes")) {
            if ("whatsapp_business_management".equals(scope.path("scope").asText())
                    || "whatsapp_business_messaging".equals(scope.path("scope").asText())) {
                scope.path("target_ids").forEach(id -> {
                    if (!ids.contains(id.asText())) ids.add(id.asText());
                });
            }
        }
        return ids;
    }

    public JsonNode getPhoneNumbers(String wabaId, String token) {
        return get(properties.apiUrl("/" + wabaId + "/phone_numbers"
                + "?fields=id,display_phone_number,verified_name,quality_rating,"
                + "code_verification_status,name_status,platform_type,messaging_limit_tier"), token);
    }

    /** Subscribes our app to the WABA so webhooks start flowing. Idempotent. */
    public void subscribeApp(String wabaId, String token) {
        postForm(properties.apiUrl("/" + wabaId + "/subscribed_apps"), token, new LinkedMultiValueMap<>());
    }

    /** Registers the number for Cloud API messaging. Already-registered is OK. */
    public void registerPhone(String phoneNumberId, String token, String pin) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("messaging_product", "whatsapp");
            body.put("pin", pin == null || pin.isBlank() ? "000000" : pin);
            postJson(properties.apiUrl("/" + phoneNumberId + "/register"), token, body);
        } catch (WhatsAppProviderException e) {
            // Meta returns an error when the number is already registered — fine.
            log.info("registerPhone non-fatal for {}: {}", phoneNumberId, e.getMessage());
        }
    }

    public JsonNode getTemplates(String wabaId, String token) {
        return get(properties.apiUrl("/" + wabaId + "/message_templates"
                + "?fields=id,name,language,category,status,components,quality_score&limit=200"), token);
    }

    /**
     * Spend analytics for a WABA over [start, end] (unix seconds). Tries the
     * per-message pricing_analytics field first (July-2025 pricing model),
     * falls back to conversation_analytics on older Graph versions. Returns
     * the raw JSON — WhatsAppUsageService normalizes both shapes.
     */
    public JsonNode getSpendAnalytics(String wabaId, String token, long start, long end) {
        try {
            return get(properties.apiUrl("/" + wabaId
                    + "?fields=pricing_analytics.start(" + start + ").end(" + end
                    + ").granularity(MONTHLY).dimensions([%22PRICING_CATEGORY%22])"), token);
        } catch (WhatsAppProviderException e) {
            log.info("pricing_analytics unavailable ({}), falling back to conversation_analytics",
                    e.getErrorCode());
            return get(properties.apiUrl("/" + wabaId
                    + "?fields=conversation_analytics.start(" + start + ").end(" + end
                    + ").granularity(MONTHLY).dimensions([%22CONVERSATION_CATEGORY%22])"
                    + "&metric_types=[%22COST%22,%22CONVERSATION%22]"), token);
        }
    }

    /** Submits a new template for Meta review. Returns {id, status, category}. */
    public JsonNode createTemplate(String wabaId, String token, Map<String, Object> payload) {
        return postJson(properties.apiUrl("/" + wabaId + "/message_templates"), token, payload);
    }

    /** Deletes a template (all languages of that name). */
    public void deleteTemplate(String wabaId, String token, String name) {
        try {
            restClient.delete()
                    .uri(properties.apiUrl("/" + wabaId + "/message_templates?name=" + name))
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw toProviderException(e);
        } catch (Exception e) {
            throw new WhatsAppProviderException("NETWORK", "Could not reach WhatsApp servers. Please try again.",
                    "DELETE message_templates failed: " + e.getMessage());
        }
    }

    /**
     * Uploads a file to Meta's media store for this phone number. Returns the
     * media id used by sendMediaMessage. Caller passes the correct mime type.
     */
    public String uploadMedia(String phoneNumberId, String token,
                              byte[] bytes, String filename, String mimeType) {
        try {
            org.springframework.core.io.ByteArrayResource resource =
                    new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override
                        public String getFilename() {
                            return filename == null ? "file" : filename;
                        }
                    };
            org.springframework.http.HttpHeaders partHeaders = new org.springframework.http.HttpHeaders();
            partHeaders.setContentType(MediaType.parseMediaType(mimeType));
            org.springframework.http.HttpEntity<org.springframework.core.io.ByteArrayResource> filePart =
                    new org.springframework.http.HttpEntity<>(resource, partHeaders);

            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("messaging_product", "whatsapp");
            form.add("type", mimeType);
            form.add("file", filePart);

            String response = restClient.post()
                    .uri(properties.apiUrl("/" + phoneNumberId + "/media"))
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            String mediaId = objectMapper.readTree(response == null ? "{}" : response)
                    .path("id").asText(null);
            if (mediaId == null) {
                throw new WhatsAppProviderException("NO_MEDIA_ID",
                        "Meta ne media accept nahi kiya.", "media upload returned no id");
            }
            return mediaId;
        } catch (RestClientResponseException e) {
            throw toProviderException(e);
        } catch (WhatsAppProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new WhatsAppProviderException("NETWORK",
                    "Could not reach WhatsApp servers. Please try again.",
                    "media upload failed: " + e.getMessage());
        }
    }

    /** mediaType: image | video | audio | document. Caption/filename optional. */
    public WhatsAppSendResult sendMediaMessage(String phoneNumberId, String token, String toPhone,
                                               String mediaType, String mediaId,
                                               String caption, String filename) {
        Map<String, Object> media = new HashMap<>();
        media.put("id", mediaId);
        if (caption != null && !caption.isBlank()
                && !"audio".equals(mediaType)) {
            media.put("caption", caption);
        }
        if ("document".equals(mediaType) && filename != null && !filename.isBlank()) {
            media.put("filename", filename);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", toPhone);
        payload.put("type", mediaType);
        payload.put(mediaType, media);
        return sendMessage(phoneNumberId, token, payload);
    }

    /* ------------------------------------------------------- message calls */

    public WhatsAppSendResult sendTextMessage(String phoneNumberId, String token,
                                              String toPhone, String body) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", toPhone);
        payload.put("type", "text");
        payload.put("text", Map.of("preview_url", false, "body", body));
        return sendMessage(phoneNumberId, token, payload);
    }

    /** Interactive quick-reply buttons (max 3) — same 24h-window rules as text. */
    public WhatsAppSendResult sendInteractiveButtons(String phoneNumberId, String token,
                                                     String toPhone, String body,
                                                     java.util.List<String> buttons) {
        java.util.List<Map<String, Object>> buttonRows = new java.util.ArrayList<>();
        int i = 0;
        for (String label : buttons) {
            if (label == null || label.isBlank()) continue;
            if (++i > 3) break;
            buttonRows.add(Map.of("type", "reply", "reply",
                    Map.of("id", "btn_" + i, "title", label.length() > 20 ? label.substring(0, 20) : label)));
        }
        Map<String, Object> interactive = new HashMap<>();
        interactive.put("type", "button");
        interactive.put("body", Map.of("text", body));
        interactive.put("action", Map.of("buttons", buttonRows));
        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", toPhone);
        payload.put("type", "interactive");
        payload.put("interactive", interactive);
        return sendMessage(phoneNumberId, token, payload);
    }

    public WhatsAppSendResult sendTemplateMessage(String phoneNumberId, String token, String toPhone,
                                                  String templateName, String language, String componentsJson) {
        Map<String, Object> template = new HashMap<>();
        template.put("name", templateName);
        template.put("language", Map.of("code", language == null || language.isBlank() ? "en" : language));
        if (componentsJson != null && !componentsJson.isBlank()) {
            try {
                template.put("components", objectMapper.readTree(componentsJson));
            } catch (Exception e) {
                return WhatsAppSendResult.failed("BAD_COMPONENTS", "Template parameters JSON is invalid.");
            }
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", toPhone);
        payload.put("type", "template");
        payload.put("template", template);
        return sendMessage(phoneNumberId, token, payload);
    }

    /**
     * One send with bounded retry: transient failures (429/5xx/network) back
     * off 1s, 2s, 4s… up to apiMaxRetries extra attempts; permanent errors
     * (bad number, unapproved template, invalid token) fail immediately.
     */
    private WhatsAppSendResult sendMessage(String phoneNumberId, String token, Map<String, Object> payload) {
        int attempts = Math.max(1, properties.getApiMaxRetries() + 1);
        WhatsAppSendResult last = WhatsAppSendResult.failed("UNKNOWN", "Send did not run");
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(1000L << (attempt - 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return last;
                }
            }
            try {
                JsonNode node = postJson(properties.apiUrl("/" + phoneNumberId + "/messages"), token, payload);
                String wamid = node.path("messages").path(0).path("id").asText(null);
                if (wamid == null) {
                    return WhatsAppSendResult.failed("NO_WAMID",
                            "Provider accepted the request but returned no message id.");
                }
                return WhatsAppSendResult.ok(wamid);
            } catch (WhatsAppProviderException e) {
                last = e.isTransient()
                        ? WhatsAppSendResult.failedTransient(e.getErrorCode(), e.getUserMessage())
                        : WhatsAppSendResult.failed(e.getErrorCode(), e.getUserMessage());
                if (!e.isTransient()) {
                    return last;
                }
                log.info("Transient Meta send error ({}) — attempt {}/{}", e.getErrorCode(),
                        attempt + 1, attempts);
            }
        }
        return last;
    }

    /* ------------------------------------------------------------ plumbing */

    private JsonNode get(String url, String token) {
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(url);
            if (token != null) spec = spec.header("Authorization", "Bearer " + token);
            String body = spec.retrieve().body(String.class);
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (RestClientResponseException e) {
            throw toProviderException(e);
        } catch (WhatsAppProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new WhatsAppProviderException("NETWORK", "Could not reach WhatsApp servers. Please try again.",
                    "GET " + redact(url) + " failed: " + e.getMessage());
        }
    }

    private JsonNode postJson(String url, String token, Map<String, Object> body) {
        try {
            String response = restClient.post().uri(url)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response == null ? "{}" : response);
        } catch (RestClientResponseException e) {
            throw toProviderException(e);
        } catch (WhatsAppProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new WhatsAppProviderException("NETWORK", "Could not reach WhatsApp servers. Please try again.",
                    "POST " + redact(url) + " failed: " + e.getMessage());
        }
    }

    private void postForm(String url, String token, MultiValueMap<String, String> form) {
        try {
            restClient.post().uri(url)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw toProviderException(e);
        } catch (Exception e) {
            throw new WhatsAppProviderException("NETWORK", "Could not reach WhatsApp servers. Please try again.",
                    "POST " + redact(url) + " failed: " + e.getMessage());
        }
    }

    private WhatsAppProviderException toProviderException(RestClientResponseException e) {
        String code = "META_" + e.getStatusCode().value();
        String userMessage = "WhatsApp request failed. Please try again.";
        String detail = e.getResponseBodyAsString();
        try {
            JsonNode err = objectMapper.readTree(detail).path("error");
            if (!err.isMissingNode()) {
                code = "META_" + err.path("code").asText(String.valueOf(e.getStatusCode().value()));
                String metaMessage = err.path("message").asText("");
                // Meta messages are generic enough to surface, minus any token echoes.
                if (!metaMessage.isBlank()) userMessage = redact(metaMessage);
            }
        } catch (Exception ignored) { /* keep generic message */ }
        log.warn("Meta API error {}: {}", code, redact(detail));
        return new WhatsAppProviderException(code, userMessage, "Meta API " + code,
                e.getStatusCode().value());
    }

    /** Strips anything that looks like a token/secret out of loggable text. */
    private String redact(String text) {
        if (text == null) return "";
        return text
                .replaceAll("(?i)(access_token=)[^&\\s\"]+", "$1***")
                .replaceAll("(?i)(client_secret=)[^&\\s\"]+", "$1***")
                .replaceAll("EAA[A-Za-z0-9]+", "EAA***");
    }
}
