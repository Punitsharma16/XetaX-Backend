package com.xetax.crm.voice.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.voice.VoiceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Locale;
import java.util.Map;

/**
 * Whisper, hosted by Groq — the same account and key the chat model already
 * uses, so the assistant needs no second provider to start talking.
 *
 * <p>The response is asked for as verbose_json purely for one field: the
 * language that was spoken. Groq spells it as a name ("hindi"), so it is
 * folded back to a code here and everything downstream deals in codes.
 */
@Component
@Slf4j
public class GroqWhisperStt implements SpeechToText {

    private final VoiceProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    /**
     * Whisper reports the language by name. Only the ones a caller here is
     * likely to actually speak are mapped; anything else falls through to the
     * first two letters, which is right far more often than it is wrong.
     */
    private static final Map<String, String> LANGUAGE_CODES = Map.ofEntries(
            Map.entry("english", "en"), Map.entry("hindi", "hi"), Map.entry("urdu", "ur"),
            Map.entry("punjabi", "pa"), Map.entry("marathi", "mr"), Map.entry("gujarati", "gu"),
            Map.entry("bengali", "bn"), Map.entry("tamil", "ta"), Map.entry("telugu", "te"),
            Map.entry("kannada", "kn"), Map.entry("malayalam", "ml"), Map.entry("odia", "or"),
            Map.entry("nepali", "ne"), Map.entry("arabic", "ar"), Map.entry("spanish", "es"),
            Map.entry("french", "fr"), Map.entry("german", "de"), Map.entry("portuguese", "pt"),
            Map.entry("russian", "ru"), Map.entry("japanese", "ja"), Map.entry("korean", "ko"),
            Map.entry("chinese", "zh"), Map.entry("indonesian", "id"), Map.entry("dutch", "nl"),
            Map.entry("italian", "it"), Map.entry("turkish", "tr"), Map.entry("thai", "th"),
            Map.entry("vietnamese", "vi"));

    public GroqWhisperStt(VoiceProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        // A long utterance on a slow link still has to land; the socket has its
        // own idle timeout above this.
        factory.setReadTimeout(60_000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public boolean isConfigured() {
        return props.getStt().getApiKey() != null && !props.getStt().getApiKey().isBlank();
    }

    @Override
    public Transcript transcribe(byte[] audio, String filename) {
        if (!isConfigured()) {
            throw new IllegalStateException("Speech recognition is not configured (GROQ_API_KEY).");
        }
        if (audio == null || audio.length == 0) {
            return new Transcript("", "en");
        }
        if (audio.length > props.getStt().getMaxBytes()) {
            throw new IllegalArgumentException("That was too long — please say it in a shorter sentence.");
        }

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new NamedResource(audio, filename == null ? "speech.m4a" : filename));
        form.add("model", props.getStt().getModel());
        // Names the language back to us, which decides both the reply language
        // and the voice that reads it.
        form.add("response_format", "verbose_json");
        // No creativity wanted from a transcriber.
        form.add("temperature", "0");

        String body = http.post()
                .uri(props.getStt().getBaseUrl() + "/audio/transcriptions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getStt().getApiKey())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(String.class);

        try {
            JsonNode node = mapper.readTree(body == null ? "{}" : body);
            String text = node.path("text").asText("").trim();
            return new Transcript(text, toCode(node.path("language").asText("")));
        } catch (Exception e) {
            log.warn("Could not read the transcription response: {}", e.getMessage());
            throw new IllegalStateException("Could not understand the audio — please try again.");
        }
    }

    /** "hindi" -> "hi"; an already-short code is passed through. */
    public static String toCode(String reported) {
        if (reported == null || reported.isBlank()) return "en";
        String key = reported.trim().toLowerCase(Locale.ROOT);
        String mapped = LANGUAGE_CODES.get(key);
        if (mapped != null) return mapped;
        if (key.length() == 2) return key;
        return key.substring(0, Math.min(2, key.length()));
    }

    /**
     * Multipart needs a filename on the file part — the provider reads the
     * extension to know the container. A bare ByteArrayResource has none.
     */
    private static final class NamedResource extends ByteArrayResource {
        private final String filename;

        NamedResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
