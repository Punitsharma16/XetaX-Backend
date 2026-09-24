package com.xetax.crm.voice.tts;

import com.xetax.crm.voice.stt.SpokenLanguage;

/**
 * Which voice reads the answer.
 *
 * <p>Two, for the two languages the assistant speaks. Both are neural voices
 * and both are Indian, which matters more than it sounds: an Indian English
 * voice reads a name, a company and a rupee figure the way the person saying
 * them does, and the Hindi voice handles an English word dropped into a Hindi
 * sentence without stumbling — which is most sentences here.
 */
public final class VoiceCatalog {

    private static final String HINDI_VOICE = "hi-IN-SwaraNeural";
    private static final String ENGLISH_VOICE = "en-IN-NeerjaNeural";

    private VoiceCatalog() {
    }

    public static String voiceFor(String language) {
        return SpokenLanguage.ENGLISH.equals(SpokenLanguage.normalise(language))
                ? ENGLISH_VOICE : HINDI_VOICE;
    }

    /** The xml:lang the SSML declares — always the voice's own locale. */
    public static String localeOf(String voice) {
        int second = voice.indexOf('-', voice.indexOf('-') + 1);
        return second > 0 ? voice.substring(0, second) : "en-IN";
    }
}
