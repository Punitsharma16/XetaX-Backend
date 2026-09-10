package com.xetax.crm.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Graph API calls for Pages, lead forms and ad insights. Separate from
 * MetaWhatsAppClient on purpose — WhatsApp messaging must not be touched by
 * changes here — but it shares the same app credentials and version setting,
 * so the Graph version is still never hardcoded.
 */
@Slf4j
@Component
public class MetaGraphClient {

    private final MetaWhatsAppProperties app;
    private final ObjectMapper mapper;
    private final RestClient http;

    public MetaGraphClient(MetaWhatsAppProperties app, ObjectMapper mapper) {
        this.app = app;
        this.mapper = mapper;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(app.getApiConnectTimeoutMs());
        factory.setReadTimeout(Math.max(app.getApiReadTimeoutMs(), 30_000));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Facebook Login for Business code → a long-lived user access token. */
    public String exchangeCode(String code, String redirectUri) {
        StringBuilder url = new StringBuilder(app.apiUrl("/oauth/access_token"))
                .append("?client_id=").append(enc(app.getAppId()))
                .append("&client_secret=").append(enc(app.getAppSecret()))
                .append("&code=").append(enc(code));
        if (redirectUri != null && !redirectUri.isBlank()) {
            url.append("&redirect_uri=").append(enc(redirectUri));
        }
        JsonNode node = get(url.toString(), null);
        String token = node.path("access_token").asText(null);
        if (token == null) throw new MetaApiException("Meta did not return an access token — please retry the connection.");
        return token;
    }

    public record PageRef(String id, String name, String accessToken, String igUserId) {}

    /** Pages the person manages, each with its own page access token. */
    public List<PageRef> pages(String userToken) {
        JsonNode node = get(app.apiUrl("/me/accounts")
                + "?fields=id,name,access_token,instagram_business_account&limit=100", userToken);
        List<PageRef> out = new ArrayList<>();
        for (JsonNode p : node.path("data")) {
            out.add(new PageRef(p.path("id").asText(), p.path("name").asText(),
                    p.path("access_token").asText(null),
                    p.path("instagram_business_account").path("id").asText(null)));
        }
        return out;
    }

    public record AdAccountRef(String id, String name, String currency) {}

    public List<AdAccountRef> adAccounts(String userToken) {
        JsonNode node = get(app.apiUrl("/me/adaccounts")
                + "?fields=account_id,name,currency&limit=100", userToken);
        List<AdAccountRef> out = new ArrayList<>();
        for (JsonNode a : node.path("data")) {
            String id = a.path("account_id").asText(null);
            if (id == null || id.isBlank()) continue;
            out.add(new AdAccountRef("act_" + id, a.path("name").asText(""), a.path("currency").asText("INR")));
        }
        return out;
    }

    /** Tells Meta to send this Page's leadgen events to our webhook. */
    public void subscribePage(String pageId, String pageToken) {
        post(app.apiUrl("/" + pageId + "/subscribed_apps"), pageToken,
                Map.of("subscribed_fields", "leadgen"));
    }

    public void unsubscribePage(String pageId, String pageToken) {
        try {
            delete(app.apiUrl("/" + pageId + "/subscribed_apps"), pageToken);
        } catch (Exception e) {
            log.warn("Page {} unsubscribe failed (ignored): {}", pageId, e.getMessage());
        }
    }

    /** The submitted lead: its answers plus which ad it came from. */
    public JsonNode lead(String leadgenId, String pageToken) {
        return get(app.apiUrl("/" + leadgenId)
                + "?fields=id,created_time,field_data,form_id,ad_id,adset_id,campaign_id,platform", pageToken);
    }

    public JsonNode formName(String formId, String pageToken) {
        return get(app.apiUrl("/" + formId) + "?fields=id,name", pageToken);
    }

    /** Per-ad, per-day spend and results for a date range. */
    public JsonNode insights(String adAccountId, String userToken, String since, String until) {
        String timeRange = URLEncoder.encode("{\"since\":\"" + since + "\",\"until\":\"" + until + "\"}",
                StandardCharsets.UTF_8);
        return get(app.apiUrl("/" + adAccountId + "/insights")
                + "?level=ad&time_increment=1&limit=500"
                + "&fields=campaign_id,campaign_name,ad_id,ad_name,spend,impressions,clicks,actions,account_currency"
                + "&time_range=" + timeRange, userToken);
    }

    /** Follows Graph paging for a result set we already fetched the first page of. */
    public JsonNode next(String url, String token) {
        return get(url, token);
    }

    /* ----------------------------------------------------------- plumbing */

    private JsonNode get(String url, String token) {
        try {
            var spec = http.get().uri(url);
            if (token != null) spec = spec.header("Authorization", "Bearer " + token);
            String body = spec.retrieve().body(String.class);
            return mapper.readTree(body == null ? "{}" : body);
        } catch (RestClientResponseException e) {
            throw toException(e, "GET " + redact(url));
        } catch (MetaApiException e) {
            throw e;
        } catch (Exception e) {
            throw new MetaApiException("Could not reach Meta. Please try again in a moment.");
        }
    }

    private void post(String url, String token, Map<String, String> form) {
        try {
            var body = new org.springframework.util.LinkedMultiValueMap<String, String>();
            form.forEach(body::add);
            http.post().uri(url)
                    .header("Authorization", "Bearer " + token)
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw toException(e, "POST " + redact(url));
        } catch (Exception e) {
            throw new MetaApiException("Could not reach Meta. Please try again in a moment.");
        }
    }

    private void delete(String url, String token) {
        http.delete().uri(url).header("Authorization", "Bearer " + token).retrieve().body(String.class);
    }

    private MetaApiException toException(RestClientResponseException e, String what) {
        String message = "Meta rejected the request.";
        try {
            JsonNode error = mapper.readTree(e.getResponseBodyAsString()).path("error");
            String m = error.path("error_user_msg").asText(error.path("message").asText(""));
            if (!m.isBlank()) message = m;
        } catch (Exception ignored) {
            /* keep the generic message */
        }
        log.warn("{} failed ({}): {}", what, e.getStatusCode().value(), message);
        return new MetaApiException(message);
    }

    private static String redact(String url) {
        return url.replaceAll("(client_secret|access_token|code)=[^&]*", "$1=***");
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
