package com.xetax.crm.voice.stt;

/**
 * What the speech recogniser heard.
 *
 * @param text     the words, already trimmed; blank when only silence or noise
 *                 was sent
 * @param language BCP-47-ish code of the language spoken ("hi", "en", "mr"),
 *                 lowercase. This is what makes the assistant answer in the
 *                 language it was addressed in, so it is carried all the way
 *                 through to the voice that reads the reply out.
 */
public record Transcript(String text, String language) {

    public boolean isBlank() {
        return text == null || text.isBlank();
    }
}
