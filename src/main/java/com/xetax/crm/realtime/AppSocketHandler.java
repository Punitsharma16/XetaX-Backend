package com.xetax.crm.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The panel's live socket at /ws/app.
 *
 * <p>Traffic is one-way in practice: the server pushes small "something
 * changed, go and read it" frames and the panel answers only with keepalive
 * pings. Nothing is written through this socket, so a lost connection costs
 * nothing but latency — the panel falls back to polling until it is back.
 */
@Component
@RequiredArgsConstructor
public class AppSocketHandler extends TextWebSocketHandler {

    /** Enough for a burst of tiny frames; a slower client is dropped, not queued forever. */
    private static final int SEND_BUFFER_BYTES = 64 * 1024;
    private static final int SEND_TIME_LIMIT_MS = 5_000;

    private final RealtimeHub hub;

    /** Raw session id → the decorated session and the keys it was filed under. */
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    private record Registration(WebSocketSession session, Set<String> keys) {}

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Object userId = session.getAttributes().get(AppSocketHandshakeInterceptor.ATTR_USER_ID);
        Object ownerId = session.getAttributes().get(AppSocketHandshakeInterceptor.ATTR_OWNER_ID);

        Set<String> keys = new LinkedHashSet<>();
        if (userId != null) keys.add(userId.toString());
        if (ownerId != null) keys.add(ownerId.toString());

        // Sends can come from any thread (a webhook, a scheduler); the
        // decorator serialises them so two events never interleave on the wire.
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_BYTES);

        registrations.put(session.getId(), new Registration(safe, keys));
        hub.register(safe, keys);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // Keepalive only — proxies close a silent connection after a minute or two.
        if ("ping".equals(message.getPayload())) {
            session.sendMessage(new TextMessage("pong"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Registration registration = registrations.remove(session.getId());
        if (registration != null) hub.unregister(registration.session(), registration.keys());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }
}
