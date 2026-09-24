package com.xetax.crm.voice.tts;

/**
 * Reads a reply out loud in the language it was written in.
 *
 * <p>Separate from the recogniser on purpose: the two have different free
 * tiers and different failure modes, and losing the voice should never stop
 * the assistant from answering — the text still reaches the screen.
 */
public interface TextToSpeech {

    /**
     * @param text     what to say
     * @param language the language code the user spoke, which picks the voice
     */
    Speech speak(String text, String language);

    boolean isConfigured();
}
