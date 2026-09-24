package com.xetax.crm.voice.socket;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Hey XetaX".
 *
 * <p>The microphone has to be open to hear the phrase at all, so the phrase is
 * found here, in the transcript, rather than on the device. Everything the
 * phone hears that does not begin with it is dropped and never reaches the
 * assistant — no tool runs, no answer is spoken, nothing is charged.
 *
 * <p>The matching is deliberately forgiving. A recogniser hands back "hey
 * zeta", "hi cheetah", "ok theta", "हे ज़ेटा" or "ہے زیٹا" for the same two
 * words, and an assistant that only wakes to the spelling in the manual is one
 * that looks broken. What it must not do is wake to ordinary conversation, so
 * a greeting alone is not enough — the name has to follow it.
 */
public final class WakeWord {

    /** What may come before the name. Any of these, or nothing at all. */
    private static final Set<String> GREETINGS = Set.of(
            "hey", "hay", "hei", "he", "hi", "hello", "hallo", "halo", "hlo", "ok", "okay",
            "arre", "are", "yo", "suno", "sun",
            "हे", "हाय", "हैलो", "हेलो", "ओके", "अरे", "सुनो",
            "ہے", "ہائے", "ہیلو", "اوکے");

    /** The name, in the scripts and misspellings it actually comes back as. */
    private static final List<String> NAMES = List.of(
            "xetax", "xeta", "zetax", "zeta", "cheetah", "cheeta", "chita", "seta", "setax",
            "jeta", "jetax", "zeeta", "zita", "xita", "exeta",
            "ज़ेटा", "जेटा", "ज़ीटा", "जीटा", "ज़ेटाक्स", "जेटाक्स", "चीता", "सेटा",
            "زیٹا", "زیتا", "چیتا", "سیٹا");

    /**
     * Everyday words that must never be mistaken for the name. "there" is two
     * edits from one plausible mishearing and "the" is two from another, so
     * without this the assistant woke up to "hey there" and to "the cheetah
     * ran fast".
     */
    private static final Set<String> NEVER_THE_NAME = Set.of(
            "the", "there", "their", "they", "them", "then", "than", "that", "this", "these",
            "here", "where", "were", "we", "she", "he", "her", "hers", "seen", "see", "set",
            "yes", "yeah", "meta", "beta", "data", "zero", "eta", "peta", "delta");

    private WakeWord() {
    }

    /**
     * What was said, once the wake phrase is taken off the front.
     *
     * @param command the rest of the sentence — blank when the user only
     *                called the assistant's name and is waiting to be asked
     */
    public record Heard(String command) {
        public boolean hasCommand() {
            return command != null && !command.isBlank();
        }
    }

    /**
     * @return what follows the wake phrase, or null when the phrase is not
     *         there at all and the words were not meant for us
     */
    public static Heard find(String transcript) {
        if (transcript == null || transcript.isBlank()) return null;

        List<String> words = List.of(clean(transcript).split("\\s+"));
        if (words.isEmpty()) return null;

        // The name is either the first word, or the second after a greeting.
        int nameAt = -1;
        if (isName(words.get(0))) {
            nameAt = 0;
        } else if (words.size() > 1 && GREETINGS.contains(words.get(0)) && isName(words.get(1))) {
            nameAt = 1;
        }
        if (nameAt < 0) return null;

        String rest = String.join(" ", words.subList(nameAt + 1, words.size()));
        return new Heard(stripLeadingFiller(rest));
    }

    /** Punctuation is noise here, and the recogniser adds plenty of it. */
    private static String clean(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}۔،؟]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isName(String word) {
        if (word.isBlank() || NEVER_THE_NAME.contains(word)) return false;

        for (String name : NAMES) {
            if (word.equals(name)) return true;
            // Only latin spellings are compared loosely; two Devanagari words
            // two edits apart are usually two different words.
            if (isLatin(word) && isLatin(name) && distance(word, name) <= slipAllowed(word)) return true;
        }
        return false;
    }

    /**
     * How far off a word may be. A short word reaches a great many others in
     * two edits, so it gets one; only a longer word has earned the benefit of
     * the doubt.
     */
    private static int slipAllowed(String word) {
        if (word.length() < 4) return 0;
        return word.length() <= 5 ? 1 : 2;
    }

    private static boolean isLatin(String word) {
        return word.chars().allMatch(c -> c < 128);
    }

    /** A comma after the name is common; so is "please" or "ji". */
    private static String stripLeadingFiller(String rest) {
        return rest.replaceFirst("^(ji|please|plz|जी|براہ کرم)\\s+", "").trim();
    }

    /** Levenshtein, iterative, one row at a time. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
