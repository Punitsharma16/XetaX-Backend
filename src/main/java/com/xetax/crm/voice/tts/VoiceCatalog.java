package com.xetax.crm.voice.tts;

import java.util.Locale;
import java.util.Map;

/**
 * Which voice reads the answer.
 *
 * <p>These are neural voices, not the old concatenative kind — the difference
 * is the whole point of the feature, because an assistant that is hard to
 * understand is one nobody uses twice. The default is Indian English rather
 * than American: it is the accent this product's users read the panel in.
 */
public final class VoiceCatalog {

    private static final String DEFAULT_VOICE = "en-IN-NeerjaNeural";

    private static final Map<String, String> BY_LANGUAGE = Map.ofEntries(
            // India
            Map.entry("en", "en-IN-NeerjaNeural"),
            Map.entry("hi", "hi-IN-SwaraNeural"),
            Map.entry("mr", "mr-IN-AarohiNeural"),
            Map.entry("gu", "gu-IN-DhwaniNeural"),
            Map.entry("bn", "bn-IN-TanishaaNeural"),
            Map.entry("ta", "ta-IN-PallaviNeural"),
            Map.entry("te", "te-IN-ShrutiNeural"),
            Map.entry("kn", "kn-IN-SapnaNeural"),
            Map.entry("ml", "ml-IN-SobhanaNeural"),
            Map.entry("or", "or-IN-SubhasiniNeural"),
            Map.entry("ur", "ur-IN-GulNeural"),
            // Punjabi has no Indian neural voice on this tier; the Hindi one is
            // far closer than falling back to English would be.
            Map.entry("pa", "hi-IN-SwaraNeural"),
            Map.entry("ne", "ne-NP-HemkalaNeural"),
            // Elsewhere
            Map.entry("ar", "ar-EG-SalmaNeural"),
            Map.entry("es", "es-ES-ElviraNeural"),
            Map.entry("fr", "fr-FR-DeniseNeural"),
            Map.entry("de", "de-DE-KatjaNeural"),
            Map.entry("pt", "pt-BR-FranciscaNeural"),
            Map.entry("it", "it-IT-ElsaNeural"),
            Map.entry("nl", "nl-NL-ColetteNeural"),
            Map.entry("ru", "ru-RU-SvetlanaNeural"),
            Map.entry("ja", "ja-JP-NanamiNeural"),
            Map.entry("ko", "ko-KR-SunHiNeural"),
            Map.entry("zh", "zh-CN-XiaoxiaoNeural"),
            Map.entry("id", "id-ID-GadisNeural"),
            Map.entry("tr", "tr-TR-EmelNeural"),
            Map.entry("th", "th-TH-PremwadeeNeural"),
            Map.entry("vi", "vi-VN-HoaiMyNeural"));

    private VoiceCatalog() {
    }

    public static String voiceFor(String language) {
        if (language == null || language.isBlank()) return DEFAULT_VOICE;
        return BY_LANGUAGE.getOrDefault(language.trim().toLowerCase(Locale.ROOT), DEFAULT_VOICE);
    }

    /** The xml:lang the SSML declares — always the voice's own locale. */
    public static String localeOf(String voice) {
        int second = voice.indexOf('-', voice.indexOf('-') + 1);
        return second > 0 ? voice.substring(0, second) : "en-IN";
    }
}
