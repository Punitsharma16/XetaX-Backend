package com.xetax.crm.voice.stt;

/**
 * Turns one recorded utterance into words.
 *
 * <p>An interface with a single implementation today, because the provider is
 * the part most likely to be swapped: a hosted Whisper now, a self-hosted one
 * later, without the socket or the assistant noticing.
 */
public interface SpeechToText {

    /**
     * @param audio    the whole utterance, in a container the provider accepts
     *                 (m4a, mp3, wav, ogg, webm, flac)
     * @param filename only used to tell the provider the container; the bytes
     *                 are never written to disk
     */
    Transcript transcribe(byte[] audio, String filename);

    /** False when no key is configured — the caller then says so out loud. */
    boolean isConfigured();
}
