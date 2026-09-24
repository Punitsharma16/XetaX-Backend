package com.xetax.crm.voice.socket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xetax.crm.auth.security.UserCacheService;
import com.xetax.crm.billing.AiQuotaService;
import com.xetax.crm.common.ratelimit.RateLimiterService;
import com.xetax.crm.voice.VoiceProperties;
import com.xetax.crm.voice.assistant.UiAction;
import com.xetax.crm.voice.assistant.VoiceAssistant;
import com.xetax.crm.voice.assistant.VoiceReply;
import com.xetax.crm.voice.config.VoiceHandshakeInterceptor;
import com.xetax.crm.voice.stt.SpeechToText;
import com.xetax.crm.voice.stt.SpokenLanguage;
import com.xetax.crm.voice.stt.Transcript;
import com.xetax.crm.voice.tts.Speech;
import com.xetax.crm.voice.tts.TextToSpeech;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The voice socket.
 *
 * <p>A turn is: the phone opens an utterance, streams the audio as it records,
 * and says it has finished. Everything after that — recognise, think, speak —
 * happens on a worker, because a turn takes seconds and the container thread
 * that delivered the frame must be free to deliver the next one. Replies go
 * back through a decorator so the worker and the container can never write to
 * the same connection at once.
 *
 * <p>Frames from the phone:
 * <pre>
 *   {"type":"start","format":"m4a"}   begin an utterance
 *   &lt;binary&gt;                         audio, in order
 *   {"type":"end"}                    that is all of it — answer me
 *   {"type":"text","text":"..."}      typed instead of spoken (same pipeline, no STT)
 *   {"type":"cancel"}                 forget it (the user talked over the answer)
 * </pre>
 *
 * <p>Frames back:
 * <pre>
 *   {"type":"ready","stt":true,"tts":true}
 *   {"type":"listening"}
 *   {"type":"transcript","text":"...","language":"hi"}
 *   {"type":"thinking"}
 *   {"type":"reply","text":"...","language":"hi","uiAction":{...}|null}
 *   {"type":"audio","format":"mp3","bytes":12345}   then one binary frame
 *   {"type":"idle"}
 *   {"type":"error","message":"..."}
 * </pre>
 */
@Component
@Slf4j
public class VoiceSocketHandler extends AbstractWebSocketHandler {

    /** One frame's worth of audio. The phone is told to chunk below this. */
    private static final int MAX_FRAME_BYTES = 256 * 1024;

    private final ObjectMapper mapper;
    private final SpeechToText speechToText;
    private final TextToSpeech textToSpeech;
    private final VoiceAssistant assistant;
    private final UserCacheService userCacheService;
    private final RateLimiterService rateLimiter;
    private final AiQuotaService quotaService;
    private final VoiceProperties props;

    private final Map<String, VoiceSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sinks = new ConcurrentHashMap<>();

    /**
     * Virtual threads: a turn is almost entirely waiting on three HTTP calls,
     * so there is nothing to gain from pooling platform threads and a hard
     * ceiling to lose.
     */
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public VoiceSocketHandler(ObjectMapper mapper,
                              SpeechToText speechToText,
                              TextToSpeech textToSpeech,
                              VoiceAssistant assistant,
                              UserCacheService userCacheService,
                              RateLimiterService rateLimiter,
                              AiQuotaService quotaService,
                              VoiceProperties props) {
        this.mapper = mapper;
        this.speechToText = speechToText;
        this.textToSpeech = textToSpeech;
        this.assistant = assistant;
        this.userCacheService = userCacheService;
        this.rateLimiter = rateLimiter;
        this.quotaService = quotaService;
        this.props = props;
    }

    // ───────────────────────────────── lifecycle ─────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        String userId = (String) raw.getAttributes().get(VoiceHandshakeInterceptor.ATTR_USER_ID);
        if (userId == null) {
            raw.close(CloseStatus.NOT_ACCEPTABLE.withReason("unauthenticated"));
            return;
        }
        // Sends can come from a worker while the container reads the next frame.
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 1024 * 1024);
        sinks.put(raw.getId(), session);
        sessions.put(raw.getId(), new VoiceSession(UUID.fromString(userId)));

        ObjectNode ready = mapper.createObjectNode();
        ready.put("type", "ready");
        ready.put("stt", speechToText.isConfigured());
        ready.put("tts", textToSpeech.isConfigured());
        ready.put("maxFrameBytes", MAX_FRAME_BYTES);
        ready.put("maxSeconds", props.getStt().getMaxSeconds());
        send(session, ready);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        sessions.remove(raw.getId());
        sinks.remove(raw.getId());
    }

    // ───────────────────────────────── frames in ─────────────────────────────

    @Override
    protected void handleBinaryMessage(WebSocketSession raw, BinaryMessage message) throws Exception {
        VoiceSession voice = sessions.get(raw.getId());
        if (voice == null) return;

        ByteBuffer payload = message.getPayload();
        byte[] chunk = new byte[payload.remaining()];
        payload.get(chunk);
        voice.append(chunk);

        if (voice.size() > props.getStt().getMaxBytes()) {
            voice.discard();
            fail(sink(raw), "That was too long — please say it in a shorter sentence.");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) throws Exception {
        VoiceSession voice = sessions.get(raw.getId());
        WebSocketSession session = sink(raw);
        if (voice == null || session == null) return;

        JsonNode frame;
        try {
            frame = mapper.readTree(message.getPayload());
        } catch (Exception e) {
            fail(session, "Unreadable message.");
            return;
        }

        switch (frame.path("type").asText("")) {
            case "start" -> {
                voice.uncancel();
                voice.discard();
                voice.setFormat(frame.path("format").asText(null));
                send(session, mapper.createObjectNode().put("type", "listening"));
            }
            case "end" -> {
                byte[] audio = voice.takeUtterance();
                if (audio.length == 0) {
                    send(session, mapper.createObjectNode().put("type", "idle"));
                    return;
                }
                startTurn(session, voice, audio, null);
            }
            case "text" -> {
                String typed = frame.path("text").asText("").trim();
                if (typed.isEmpty()) {
                    send(session, mapper.createObjectNode().put("type", "idle"));
                    return;
                }
                startTurn(session, voice, null, typed);
            }
            case "cancel" -> {
                // Barge-in: the answer in flight stops mattering the moment the
                // user starts talking again.
                voice.cancel();
                voice.discard();
                send(session, mapper.createObjectNode().put("type", "idle"));
            }
            case "ping" -> send(session, mapper.createObjectNode().put("type", "pong"));
            default -> fail(session, "Unknown message type.");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession raw, Throwable exception) {
        log.debug("Voice socket error: {}", exception.getMessage());
    }

    // ───────────────────────────────── one turn ──────────────────────────────

    private void startTurn(WebSocketSession session, VoiceSession voice, byte[] audio, String typed) {
        if (!voice.claimTurn()) {
            // Still answering the last thing; the phone should have cancelled.
            fail(session, "One moment — I am still on the last one.");
            return;
        }
        workers.submit(() -> {
            try {
                runTurn(session, voice, audio, typed);
            } catch (Exception e) {
                log.warn("Voice turn failed: {}", e.toString());
                fail(session, friendly(e));
            } finally {
                voice.releaseTurn();
                send(session, mapper.createObjectNode().put("type", "idle"));
            }
        });
    }

    private void runTurn(WebSocketSession session, VoiceSession voice, byte[] audio, String typed) {
        /*
         * The acting identity is rebuilt here, on this worker, from the id the
         * handshake verified. Every CRM tool reads the SecurityContext, so
         * without this the whole toolset would run as nobody — and a thread
         * that kept it would hand it to the next user's turn.
         */
        var user = userCacheService.findById(voice.userId()).orElse(null);
        if (user == null) {
            fail(session, "Your session has ended. Please sign in again.");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));

        // Answering a command ends the wake; being greeted does not.
        boolean stayAwake = false;
        try {
            // Same meters as the typed assistant — voice is not a way around them.
            rateLimiter.check("voice:" + voice.userId(), 20, Duration.ofMinutes(1));

            String spoken;
            String language;
            if (typed != null) {
                // Typing is an explicit act; it needs no wake phrase.
                spoken = typed;
                language = voice.language() == null ? props.getDefaultLanguage() : voice.language();
                voice.wake();
            } else {
                Transcript heard = speechToText.transcribe(audio, "speech." + voice.format());
                if (heard.isBlank()) {
                    sleepAndIdle(session, voice);
                    return;
                }
                spoken = heard.text();
                language = SpokenLanguage.settle(heard.language(), spoken,
                        voice.language(), props.getDefaultLanguage());

                /*
                 * Asleep, the only thing worth hearing is our own name. Anything
                 * else was said to somebody in the room, and answering it would
                 * mean running tools and spending credits on a conversation the
                 * assistant was never part of.
                 */
                if (!voice.isAwake()) {
                    WakeWord.Heard wake = WakeWord.find(spoken);
                    if (wake == null) {
                        send(session, mapper.createObjectNode().put("type", "ignored"));
                        return;
                    }
                    voice.wake();
                    voice.rememberLanguage(language);
                    if (!wake.hasCommand()) {
                        // Called by name and nothing more — open the microphone
                        // and say so, rather than silently waiting. This turn
                        // must NOT end the woken state: the command is coming.
                        send(session, mapper.createObjectNode().put("type", "awake"));
                        answerWithoutThinking(session, voice, greeting(language), language);
                        stayAwake = true;
                        return;
                    }
                    // "Hey XetaX, aaj ke task dikhao" — the command came with it.
                    spoken = wake.command();
                    send(session, mapper.createObjectNode().put("type", "awake"));
                }

                ObjectNode t = mapper.createObjectNode();
                t.put("type", "transcript");
                t.put("text", spoken);
                t.put("language", language);
                send(session, t);
            }

            voice.rememberLanguage(language);
            if (voice.isCancelled()) return;
            send(session, mapper.createObjectNode().put("type", "thinking"));

            quotaService.consumeAssistant(voice.userId().toString());
            VoiceReply reply = assistant.answer(voice.conversationId(), spoken, language, voice.userId());

            if (voice.isCancelled()) return;
            send(session, replyFrame(reply));

            // Audio last: the screen has already moved and the text is already
            // on it, so a slow or failed voice costs nothing but the voice.
            speak(session, voice, reply.text(), reply.language());
        } finally {
            // One wake, one command. The next thing said needs the phrase again.
            if (!stayAwake) voice.sleep();
            SecurityContextHolder.clearContext();
        }
    }

    /** Sends a spoken line with no round trip to the model behind it. */
    private void answerWithoutThinking(WebSocketSession session, VoiceSession voice,
                                       String text, String language) {
        ObjectNode reply = mapper.createObjectNode();
        reply.put("type", "reply");
        reply.put("text", text);
        reply.put("language", language);
        reply.putNull("uiAction");
        // Tells the phone to keep the microphone open: this was an invitation
        // to speak, not an answer to something.
        reply.put("greeting", true);
        send(session, reply);
        speak(session, voice, text, language);
    }

    private void speak(WebSocketSession session, VoiceSession voice, String text, String language) {
        Speech speech = textToSpeech.speak(text, language);
        if (speech.isEmpty() || voice.isCancelled()) return;
        ObjectNode header = mapper.createObjectNode();
        header.put("type", "audio");
        header.put("format", speech.format());
        header.put("bytes", speech.audio().length);
        send(session, header);
        sendBinary(session, speech.audio());
    }

    /** What the assistant says when it is called by name and nothing more. */
    private static String greeting(String language) {
        return SpokenLanguage.ENGLISH.equals(SpokenLanguage.normalise(language))
                ? "Yes, go ahead." : "जी, बोलिए।";
    }

    /** Nothing was heard — drop back to waiting for the wake phrase. */
    private void sleepAndIdle(WebSocketSession session, VoiceSession voice) {
        voice.sleep();
        send(session, mapper.createObjectNode().put("type", "idle"));
    }

    private ObjectNode replyFrame(VoiceReply reply) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "reply");
        node.put("text", reply.text());
        node.put("language", reply.language());
        UiAction action = reply.uiAction();
        if (action == null) {
            node.putNull("uiAction");
        } else {
            ObjectNode ui = node.putObject("uiAction");
            ui.put("screen", action.screen().name());
            ui.put("route", action.screen().route());
            ui.put("id", action.id());
            ui.put("query", action.query());
        }
        return node;
    }

    // ───────────────────────────────── plumbing ──────────────────────────────

    private WebSocketSession sink(WebSocketSession raw) {
        return sinks.get(raw.getId());
    }

    private void send(WebSocketSession session, ObjectNode node) {
        if (session == null || !session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(node)));
        } catch (Exception e) {
            log.debug("Could not send voice frame: {}", e.getMessage());
        }
    }

    private void sendBinary(WebSocketSession session, byte[] bytes) {
        if (session == null || !session.isOpen()) return;
        try {
            for (int offset = 0; offset < bytes.length; offset += MAX_FRAME_BYTES) {
                int length = Math.min(MAX_FRAME_BYTES, bytes.length - offset);
                session.sendMessage(new BinaryMessage(ByteBuffer.wrap(bytes, offset, length)));
            }
        } catch (Exception e) {
            log.debug("Could not send voice audio: {}", e.getMessage());
        }
    }

    private void fail(WebSocketSession session, String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "error");
        node.put("message", message);
        send(session, node);
    }

    /**
     * What the user hears when something breaks. Provider errors and stack
     * traces are for the log; the phone gets a sentence it can act on.
     */
    private String friendly(Exception e) {
        String message = e.getMessage();
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            return message == null ? "Something went wrong — please try again." : message;
        }
        return "Something went wrong — please try again.";
    }
}
