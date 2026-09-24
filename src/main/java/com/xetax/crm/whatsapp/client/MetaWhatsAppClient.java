package com.xetax.crm.whatsapp.client;

import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.web.util.UriComponentsBuilder;

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
            return get(pricingAnalyticsPath(wabaId, start, end), token);
        } catch (WhatsAppProviderException e) {
            log.info("pricing_analytics unavailable ({}), falling back to conversation_analytics",
                    e.getErrorCode());
            return get(conversationAnalyticsPath(wabaId, start, end), token);
        }
    }

    /*
     * The quotes around a dimension are written as quotes, not as %22.
     *
     * <p>RestClient percent-encodes the URL it is handed, so a %22 already in
     * the string came out the other side as %2522 — Meta then read the literal
     * characters "%22PRICING_CATEGORY%22" and answered "The parameter
     * dimensions must be an array", for both the pricing call and the
     * conversation fallback. Left plain, the encoder produces the %22 itself.
     */
    String pricingAnalyticsPath(String wabaId, long start, long end) {
        return properties.apiUrl("/" + wabaId
                + "?fields=pricing_analytics.start(" + start + ").end(" + end
                + ").granularity(MONTHLY).dimensions([\"PRICING_CATEGORY\"])");
    }

    String conversationAnalyticsPath(String wabaId, long start, long end) {
        return properties.apiUrl("/" + wabaId
                + "?fields=conversation_analytics.start(" + start + ").end(" + end
                + ").granularity(MONTHLY).dimensions([\"CONVERSATION_CATEGORY\"])"
                + "&metric_types=[\"COST\",\"CONVERSATION\"]");
    }

    /** Submits a new template for Meta review. Returns {id, status, category}. */
    /**
     * Uploads a sample for a media header and returns Meta's file handle.
     *
     * <p>Template review is not the same pipeline as sending: a reviewer has
     * to see an example of the image, so it goes through the Resumable Upload
     * API against the APP (not the business number), and the handle it returns
     * is what the template's `example.header_handle` carries. Two calls —
     * open a session, then push the bytes at offset 0.
     */
    public String uploadTemplateSample(byte[] bytes, String filename, String mimeType) {
        String appToken = properties.getAppId() + "|" + properties.getAppSecret();
        if (properties.getAppId().isBlank() || properties.getAppSecret().isBlank()) {
            throw new WhatsAppProviderException("NO_APP_CREDENTIALS",
                    "Sample upload needs the Meta app id and secret to be configured.",
                    "META_APP_ID / META_APP_SECRET missing");
        }
        try {
            String sessionUri = UriComponentsBuilder
                    .fromUriString(properties.apiUrl("/" + properties.getAppId() + "/uploads"))
                    .queryParam("file_name", filename == null ? "sample" : filename)
                    .queryParam("file_length", bytes.length)
                    .queryParam("file_type", mimeType)
                    .build()
                    .encode()
                    .toUriString();

            System.out.println("sessionUri = " + sessionUri);

            String sessionBody = restClient.post()
                    .uri(sessionUri)
                    .header("Authorization", "OAuth " + appToken)
                    .retrieve()
                    .body(String.class);
            String sessionId = objectMapper.readTree(sessionBody == null ? "{}" : sessionBody)
                    .path("id").asText(null);
            if (sessionId == null || sessionId.isBlank()) {
                throw new WhatsAppProviderException("NO_UPLOAD_SESSION",
                        "Meta did not start the upload. Please try again.",
                        "uploads returned no id");
            }

            String uploadBody = restClient.post()
                    .uri(properties.apiUrl("/" + sessionId))
                    .header("Authorization", "OAuth " + appToken)
                    .header("file_offset", "0")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(bytes)
                    .retrieve()
                    .body(String.class);
            String handle = objectMapper.readTree(uploadBody == null ? "{}" : uploadBody)
                    .path("h").asText(null);
            if (handle == null || handle.isBlank()) {
                throw new WhatsAppProviderException("NO_UPLOAD_HANDLE",
                        "Meta accepted the file but returned no handle.",
                        "upload returned no h");
            }
            return handle;
        } catch (RestClientResponseException e) {
            throw toProviderException(e);
        } catch (WhatsAppProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new WhatsAppProviderException("NETWORK",
                    "Could not reach Meta to upload the sample. Please try again.",
                    "sample upload failed: " + e.getMessage());
        }
    }

    public JsonNode createTemplate(String wabaId, String token, Map<String, Object> payload) {
        return postJson(properties.apiUrl("/" + wabaId + "/message_templates"), token, payload);
    }

    /* ------------------------------------------------------------- flows */

    /** Every Flow on the WABA, with the fields the panel lists. */
    public JsonNode listFlows(String wabaId, String token) {
        return get(properties.apiUrl("/" + wabaId + "/flows"
                + "?fields=id,name,status,categories,validation_errors&limit=200"), token);
    }

    /**
     * Creates the Flow shell. The screens are uploaded separately as an
     * asset, because Meta validates the JSON on upload and returns the errors
     * there rather than at creation.
     */
    public JsonNode createFlow(String wabaId, String token, String name, List<String> categories) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name);
        payload.put("categories", categories);
        return postJson(properties.apiUrl("/" + wabaId + "/flows"), token, payload);
    }

    public JsonNode updateFlowMetadata(String flowId, String token, String name, List<String> categories) {
        Map<String, Object> payload = new HashMap<>();
        if (name != null && !name.isBlank()) payload.put("name", name);
        if (categories != null && !categories.isEmpty()) payload.put("categories", categories);
        return postJson(properties.apiUrl("/" + flowId), token, payload);
    }

    /**
     * Uploads the screens. Meta answers 200 with a `validation_errors` array
     * even when it accepted the file, so the caller has to read that array —
     * an empty one is what "this Flow can be published" actually means.
     */
    public JsonNode uploadFlowJson(String flowId, String token, String flowJson) {
        try {
            byte[] bytes = flowJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            org.springframework.core.io.ByteArrayResource resource =
                    new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override
                        public String getFilename() {
                            return "flow.json";
                        }
                    };
            org.springframework.http.HttpHeaders partHeaders = new org.springframework.http.HttpHeaders();
            partHeaders.setContentType(MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<org.springframework.core.io.ByteArrayResource> filePart =
                    new org.springframework.http.HttpEntity<>(resource, partHeaders);

            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("name", "flow.json");
            form.add("asset_type", "FLOW_JSON");
            form.add("file", filePart);

            String response = restClient.post()
                    .uri(properties.apiUrl("/" + flowId + "/assets"))
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response == null ? "{}" : response);
        } catch (RestClientResponseException e) {
            throw toProviderException(e);
        } catch (Exception e) {
            throw new WhatsAppProviderException("NETWORK",
                    "Could not upload the Flow screens. Please try again.",
                    "flow asset upload failed: " + e.getMessage());
        }
    }

    /** Publishing freezes the Flow — after this only a new version can change it. */
    public JsonNode publishFlow(String flowId, String token) {
        return postJson(properties.apiUrl("/" + flowId + "/publish"), token, new HashMap<>());
    }

    /** Retires a published Flow. A deprecated Flow can no longer be sent. */
    public JsonNode deprecateFlow(String flowId, String token) {
        return postJson(properties.apiUrl("/" + flowId + "/deprecate"), token, new HashMap<>());
    }

    /** A web preview URL the panel can open in an iframe to try the Flow. */
    public JsonNode flowPreview(String flowId, String token) {
        return get(properties.apiUrl("/" + flowId + "?fields=preview.invalidate(false)"), token);
    }

    public void deleteFlow(String flowId, String token) {
        try {
            restClient.delete()
                    .uri(properties.apiUrl("/" + flowId))
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw toProviderException(e);
        } catch (Exception e) {
            throw new WhatsAppProviderException("NETWORK",
                    "Could not reach WhatsApp servers. Please try again.",
                    "flow delete failed: " + e.getMessage());
        }
    }

    /**
     * Sends a Flow as an interactive message.
     *
     * <p>flowToken is ours, not Meta's: it comes back untouched on the reply,
     * and is how a submitted form is matched to the customer it was sent to.
     */
    public WhatsAppSendResult sendFlowMessage(String phoneNumberId, String token, String toPhone,
                                              String flowId, String flowToken, String ctaText,
                                              String bodyText, String headerText, String footerText,
                                              String firstScreen, Map<String, Object> screenData) {
        Map<String, Object> actionPayload = new HashMap<>();
        actionPayload.put("screen", firstScreen == null || firstScreen.isBlank()
                ? "FIRST_ENTRY_SCREEN" : firstScreen);
        if (screenData != null && !screenData.isEmpty()) actionPayload.put("data", screenData);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("flow_message_version", "3");
        parameters.put("flow_token", flowToken);
        parameters.put("flow_id", flowId);
        parameters.put("flow_cta", ctaText == null || ctaText.isBlank() ? "Open" : ctaText);
        parameters.put("flow_action", "navigate");
        parameters.put("flow_action_payload", actionPayload);

        Map<String, Object> interactive = new HashMap<>();
        interactive.put("type", "flow");
        if (headerText != null && !headerText.isBlank()) {
            interactive.put("header", Map.of("type", "text", "text", headerText));
        }
        interactive.put("body", Map.of("text", bodyText == null || bodyText.isBlank()
                ? "Please fill this in" : bodyText));
        if (footerText != null && !footerText.isBlank()) {
            interactive.put("footer", Map.of("text", footerText));
        }
        interactive.put("action", Map.of("name", "flow", "parameters", parameters));

        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", toPhone);
        payload.put("type", "interactive");
        payload.put("interactive", interactive);
        return sendMessage(phoneNumberId, token, payload);
    }

    /**
     * Edits an existing template in place.
     *
     * <p>Meta addresses an edit by the template's own id, not by name, and
     * replaces every component with the ones in this payload — there is no way
     * to change one component on its own. Name and language cannot be changed
     * at all, and the category only while the template is not approved.
     */
    public JsonNode updateTemplate(String metaTemplateId, String token, Map<String, Object> payload) {
        return postJson(properties.apiUrl("/" + metaTemplateId), token, payload);
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
                        "Meta did not accept the media.", "media upload returned no id");
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
    /**
     * Inbound media: the id from a webhook → a short-lived Graph URL plus the
     * file's mime type and size. The URL expires within minutes, so the
     * download has to follow straight away.
     */
    public JsonNode getMediaMetadata(String mediaId, String token) {
        return get(properties.apiUrl("/" + mediaId), token);
    }

    /**
     * Downloads the bytes behind a media URL. The URL is on Meta's CDN but
     * still needs the workspace's bearer token — which is exactly why these
     * files cannot be shown to a browser directly.
     */
    public byte[] downloadMedia(String url, String token) {
        try {
            return restClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            throw toProviderException(e);
        } catch (Exception e) {
            throw new WhatsAppProviderException("NETWORK",
                    "Could not download the media from Meta.",
                    "media download failed: " + e.getMessage());
        }
    }

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
                // Plain lists and maps, never a JsonNode. The request body is
                // written by the application's own JSON mapper, which is not
                // the one that parsed this string; it has no idea what a
                // foreign tree is and wrote the node's getters out instead
                // ("array":true,"nodeType":"ARRAY"…), so Meta rejected every
                // template that carried components — an image header always.
                template.put("components", objectMapper.readValue(componentsJson,
                        new TypeReference<List<Map<String, Object>>>() { }));
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
