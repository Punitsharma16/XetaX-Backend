package com.xetax.crm.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is connected, and how a server-side event reaches them.
 *
 * <p>A panel registers under two keys: the signed-in user's own id (personal
 * things — the notification bell) and the data-owner id (workspace things a
 * whole team shares — a WhatsApp message arriving on the company number). A
 * solo user's two keys are the same id, which costs nothing.
 *
 * <p>Every publish is best effort. A dead socket, a full send buffer or a
 * serialisation slip must never break the business call that triggered it, so
 * failures are logged and swallowed — the panel's fallback poll still catches
 * whatever a lost frame would have delivered.
 */
@Component
@Slf4j
public class RealtimeHub {

    /** key (user id or data-owner id) → live sessions. */
    private final Map<String, Set<WebSocketSession>> sessionsByKey = new ConcurrentHashMap<>();

    void register(WebSocketSession session, Set<String> keys) {
        for (String key : keys) {
            sessionsByKey.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(session);
        }
    }

    void unregister(WebSocketSession session, Set<String> keys) {
        for (String key : keys) {
            Set<WebSocketSession> sessions = sessionsByKey.get(key);
            if (sessions == null) continue;
            sessions.remove(session);
            if (sessions.isEmpty()) sessionsByKey.remove(key, sessions);
        }
    }

    /** True when at least one panel of that user/workspace is listening. */
    public boolean isConnected(String key) {
        Set<WebSocketSession> sessions = sessionsByKey.get(key);
        return sessions != null && !sessions.isEmpty();
    }

    /**
     * Send once the surrounding transaction commits, so a panel that reacts
     * instantly never queries for a row the database has not published yet.
     * Outside a transaction this sends straight away.
     */
    public void publishAfterCommit(String key, String type, Map<String, Object> data) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(key, type, data);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish(key, type, data);
            }
        });
    }

    public void publish(String key, String type, Map<String, Object> data) {
        if (key == null || key.isBlank()) return;
        Set<WebSocketSession> sessions = sessionsByKey.get(key);
        if (sessions == null || sessions.isEmpty()) return;

        String frame = toJson(type, data);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(frame));
                } else {
                    sessions.remove(session);
                }
            } catch (Exception e) {
                sessions.remove(session);
                log.debug("Realtime send failed, dropping session: {}", e.getMessage());
            }
        }
    }

    /**
     * Hand-rolled rather than routed through an ObjectMapper: a frame is a
     * flat handful of ids and short strings, and this keeps the hub free of
     * any Jackson version question.
     */
    private static String toJson(String type, Map<String, Object> data) {
        StringBuilder sb = new StringBuilder(96).append("{\"type\":\"").append(escape(type)).append('"');
        if (data != null) {
            for (Map.Entry<String, Object> e : data.entrySet()) {
                sb.append(",\"").append(escape(e.getKey())).append("\":");
                Object v = e.getValue();
                if (v == null) sb.append("null");
                else if (v instanceof Number || v instanceof Boolean) sb.append(v);
                else sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
