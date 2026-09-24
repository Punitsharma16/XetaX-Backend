package com.xetax.crm.voice.stt;

import java.util.Locale;
import java.util.Set;

/**
 * Which language to answer in.
 *
 * <p>The assistant speaks two: Hindi and English — and, in practice, the mix
 * of the two that people here actually talk in. Hinglish needs no code of its
 * own: whichever of the two the recogniser lands on, the reply mirrors how the
 * user spoke and the voice reads a mixed sentence perfectly well.
 *
 * <p>Narrowing to two also settles the bug that started this. Hindi and Urdu
 * are the same spoken language written two ways; Whisper flips between them
 * freely, and when it chose Urdu the reply came back in a script the person
 * could not read. With only two to choose from, that cannot happen.
 */
public final class SpokenLanguage {

    public static final String HINDI = "hi";
    public static final String ENGLISH = "en";

    /** Below this many words the guess is not worth acting on. */
    private static final int ENOUGH_WORDS = 3;

    /** Codes Whisper reports for English, including its regional variants. */
    private static final Set<String> ENGLISH_CODES = Set.of("en", "eng", "en-in", "en-us", "en-gb");

    private SpokenLanguage() {
    }

    /**
     * @param detected  what the recogniser reported
     * @param text      what was actually said
     * @param carriedOn the language of the conversation so far, or null
     * @param fallback  the account's language when there is nothing to go on
     */
    public static String settle(String detected, String text, String carriedOn, String fallback) {
        if (wordsIn(text) < ENOUGH_WORDS) {
            /*
             * "hello", "haan", "ok" carry almost no signal, and a two-word
             * reply that flips the conversation into the other language is
             * worse than one that simply carries on.
             */
            if (carriedOn != null && !carriedOn.isBlank()) return normalise(carriedOn);
            return normalise(fallback);
        }
        return normalise(detected);
    }

    /** Everything the assistant supports is one of two things. */
    public static String normalise(String code) {
        if (code == null || code.isBlank()) return HINDI;
        String lower = code.trim().toLowerCase(Locale.ROOT);
        return ENGLISH_CODES.contains(lower) ? ENGLISH : HINDI;
    }

    static int wordsIn(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }
}
