package com.xetax.crm.voice.tts;

/** Spoken audio, ready to play. */
public record Speech(byte[] audio, String format) {

    public static Speech none() {
        return new Speech(new byte[0], "mp3");
    }

    public boolean isEmpty() {
        return audio == null || audio.length == 0;
    }
}
