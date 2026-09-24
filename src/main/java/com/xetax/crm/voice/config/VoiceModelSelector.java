package com.xetax.crm.voice.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.voice.VoiceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides which model the voice assistant actually runs on.
 *
 * <p>A model name is a string, and a wrong one is not rejected until a user
 * has already spoken: every turn comes back "404: the model does not exist or
 * you do not have access to it", and the assistant is simply mute. That is a
 * bad way to find out. So the name is checked once, at startup, against the
 * models this account really has — and if it is not one of them the assistant
 * falls back to the model the rest of the application already uses rather than
 * shipping a feature that cannot answer.
 */
@Component
@Slf4j
public class VoiceModelSelector {

    private final VoiceProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    public VoiceModelSelector(VoiceProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        var factory = new SimpleClientHttpRequestFactory();
        // Boot must not hang on this; an unreachable provider is handled below.
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * @param configured what voice.model asks for; blank means "whatever the
     *                   application already uses"
     * @return the model to run on, or null to leave the global one in place
     */
    public String choose(String configured) {
        return decide(configured, available());
    }

    /**
     * The decision itself, with the provider's answer already in hand.
     *
     * @param available the account's model ids; empty means the provider could
     *                  not be asked, which is not the same as "not there"
     */
    static String decide(String configured, Set<String> available) {
        if (configured == null || configured.isBlank()) return null;
        String wanted = configured.trim();

        if (available.isEmpty()) {
            // Could not ask. Trust the configuration rather than override it,
            // but say so, because this is the one moment it could be checked.
            log.warn("Could not verify the voice model '{}' with the provider — using it as configured.", wanted);
            return wanted;
        }
        if (available.contains(wanted)) {
            log.info("Voice assistant running on '{}'.", wanted);
            return wanted;
        }

        log.error("Voice model '{}' is not available on this account — falling back to the "
                + "application's own model. Available: {}", wanted, available);
        return null;
    }

    /** Model ids this API key can actually use; empty when the call fails. */
    private Set<String> available() {
        String key = props.getStt().getApiKey();
        if (key == null || key.isBlank()) return Set.of();
        try {
            String body = http.get()
                    .uri(props.getStt().getBaseUrl() + "/models")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .retrieve()
                    .body(String.class);
            JsonNode data = mapper.readTree(body == null ? "{}" : body).path("data");
            Set<String> ids = new LinkedHashSet<>();
            data.forEach(node -> ids.add(node.path("id").asText()));
            return ids;
        } catch (Exception e) {
            log.debug("Model list unavailable: {}", e.getMessage());
            return Set.of();
        }
    }
}
