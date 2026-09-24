package com.xetax.crm.voice.socket;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One open microphone.
 *
 * <p>Holds the utterance being recorded and the conversation it belongs to.
 * The conversation id lives for as long as the socket does, which is what lets
 * "uske liye task bana do" know who "uske" is — and what makes closing the
 * screen a clean way to start over.
 */
public class VoiceSession {

    private final UUID userId;
    private final String conversationId;
    private final ByteArrayOutputStream utterance = new ByteArrayOutputStream(64 * 1024);

    /** True from the moment a turn starts until its audio has been sent back. */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /** Set when the user talks over the assistant — the turn in flight is dropped. */
    private volatile boolean cancelled = false;

    /**
     * False until the user says the wake phrase, and false again the moment a
     * command has been answered. While asleep the microphone is still open —
     * it has to be, to hear the phrase — but nothing reaches the assistant,
     * no tool runs and nothing is charged.
     */
    private volatile boolean awake = false;

    /**
     * The language the conversation has been in. A two-word reply carries too
     * little signal to change it, so it is carried forward instead of guessed
     * afresh every turn.
     */
    private volatile String language = null;

    private volatile String format = "m4a";

    public VoiceSession(UUID userId) {
        this.userId = userId;
        // Per socket, not per user: two devices are two conversations, and a
        // reopened screen starts fresh rather than resuming a stale thread.
        this.conversationId = "voice:" + userId + ":" + UUID.randomUUID();
    }

    public UUID userId() { return userId; }
    public String conversationId() { return conversationId; }

    public boolean claimTurn() { return busy.compareAndSet(false, true); }
    public void releaseTurn() { busy.set(false); }
    public boolean isBusy() { return busy.get(); }

    public boolean isAwake() { return awake; }
    public void wake() { awake = true; }
    public void sleep() { awake = false; }

    public String language() { return language; }
    public void rememberLanguage(String code) {
        if (code != null && !code.isBlank()) this.language = code;
    }

    public void cancel() { cancelled = true; }
    public void uncancel() { cancelled = false; }
    public boolean isCancelled() { return cancelled; }

    public String format() { return format; }
    public void setFormat(String format) {
        if (format != null && !format.isBlank()) this.format = format.trim();
    }

    public synchronized void append(byte[] chunk) {
        utterance.write(chunk, 0, chunk.length);
    }

    public synchronized int size() {
        return utterance.size();
    }

    /** Hands over the recording and leaves the buffer empty for the next one. */
    public synchronized byte[] takeUtterance() {
        byte[] bytes = utterance.toByteArray();
        utterance.reset();
        return bytes;
    }

    public synchronized void discard() {
        utterance.reset();
    }
}
