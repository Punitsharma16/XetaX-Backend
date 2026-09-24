package com.xetax.crm.voice.assistant;

/**
 * One turn's answer: the words, the language they are in, and optionally
 * something for the screen to do.
 */
public record VoiceReply(String text, String language, UiAction uiAction) {

    public static VoiceReply of(String text, String language) {
        return new VoiceReply(text, language, null);
    }
}
