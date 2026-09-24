package com.xetax.crm.voice.tts;

import com.xetax.crm.voice.VoiceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Azure Speech neural voices, on the free tier.
 *
 * <p>Half a million characters a month costs nothing, which at a couple of
 * spoken sentences a turn is several thousand conversations — enough that the
 * assistant can be used properly before anyone has to think about a bill.
 *
 * <p>A failure here is deliberately not fatal: the socket already has the
 * written reply, so a silent turn is a degraded turn, not a broken one.
 */
@Component
@Slf4j
public class AzureNeuralTts implements TextToSpeech {

    private final VoiceProperties props;
    private final RestClient http;

    public AzureNeuralTts(VoiceProperties props) {
        this.props = props;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(20_000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public boolean isConfigured() {
        var tts = props.getTts();
        return tts.getApiKey() != null && !tts.getApiKey().isBlank()
                && tts.getRegion() != null && !tts.getRegion().isBlank();
    }

    @Override
    public Speech speak(String text, String language) {
        if (!isConfigured() || text == null || text.isBlank()) return Speech.none();

        String spoken = trim(text, props.getTts().getMaxChars());
        String voice = VoiceCatalog.voiceFor(language);

        try {
            byte[] audio = http.post()
                    .uri("https://" + props.getTts().getRegion()
                            + ".tts.speech.microsoft.com/cognitiveservices/v1")
                    .header("Ocp-Apim-Subscription-Key", props.getTts().getApiKey())
                    .header("X-Microsoft-OutputFormat", props.getTts().getOutputFormat())
                    .header("User-Agent", "xetax-voice")
                    .contentType(MediaType.valueOf("application/ssml+xml"))
                    .body(ssml(spoken, voice))
                    .retrieve()
                    .body(byte[].class);

            return audio == null || audio.length == 0 ? Speech.none() : new Speech(audio, "mp3");
        } catch (Exception e) {
            // The written answer still goes out; only the audio is lost.
            log.warn("Could not synthesise speech ({}): {}", voice, e.getMessage());
            return Speech.none();
        }
    }

    /**
     * A reply read aloud has to end. Ending on a finished sentence always
     * sounds better than stopping mid-word, so a full stop inside the
     * allowance wins — unless taking it would throw away most of what was
     * said, which is what the third is for: "Ok." followed by a long answer
     * must not be spoken as just "Ok."
     */
    public static String trim(String text, int maxChars) {
        String clean = text.trim();
        if (clean.length() <= maxChars) return clean;
        String cut = clean.substring(0, maxChars);
        int lastStop = Math.max(cut.lastIndexOf('.'), Math.max(cut.lastIndexOf('?'), cut.lastIndexOf('!')));
        return lastStop >= maxChars / 3 ? cut.substring(0, lastStop + 1) : cut.trim() + "…";
    }

    private String ssml(String text, String voice) {
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis'"
                + " xml:lang='" + VoiceCatalog.localeOf(voice) + "'>"
                + "<voice name='" + voice + "'>"
                + "<prosody rate='" + props.getTts().getRate() + "' pitch='" + props.getTts().getPitch() + "'>"
                + escape(text)
                + "</prosody></voice></speak>";
    }

    /** The reply is user-influenced text going into XML — it must be escaped. */
    public static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
