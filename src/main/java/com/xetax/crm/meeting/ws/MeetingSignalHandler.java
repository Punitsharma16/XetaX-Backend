package com.xetax.crm.meeting.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xetax.crm.meeting.service.MeetingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebRTC signaling relay. The server never touches media — it only forwards
 * small JSON messages (offer/answer/ICE) between the participants of a room,
 * so one instance comfortably signals hundreds of concurrent meetings.
 *
 * Handshake query params (validated in {@link MeetingHandshakeInterceptor}):
 * room, t (guest/host token), name.
 *
 * Messages in:  {type: offer|answer|ice, to, ...payload} | {type: media-state, ...}
 * Messages out: welcome {you, peers[]} | peer-joined | peer-left |
 *               offer/answer/ice (with from) | media-state | room-full
 */
@Slf4j
@Component
public class MeetingSignalHandler extends TextWebSocketHandler {

    private final MeetingService meetingService;
    private final ObjectMapper objectMapper;
    private final int maxParticipants;

    /** roomCode -> (sessionId -> session). Sessions are server-local. */
    private final Map<String, Map<String, WebSocketSession>> rooms = new ConcurrentHashMap<>();

    public MeetingSignalHandler(MeetingService meetingService, ObjectMapper objectMapper,
                                @Value("${app.meetings.max-participants:5}") int maxParticipants) {
        this.meetingService = meetingService;
        this.objectMapper = objectMapper;
        this.maxParticipants = maxParticipants;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String room = attr(session, "room");
        String name = attr(session, "name");

        Map<String, WebSocketSession> members =
                rooms.computeIfAbsent(room, key -> new ConcurrentHashMap<>());

        if (members.size() >= maxParticipants) {
            send(session, msg("room-full").put("max", maxParticipants));
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // Tell the newcomer who is already here (they will send the offers).
        ObjectNode welcome = msg("welcome").put("you", session.getId());
        var peers = welcome.putArray("peers");
        members.forEach((peerId, peer) -> peers.addObject()
                .put("id", peerId)
                .put("name", attr(peer, "name")));
        send(session, welcome);

        members.put(session.getId(), session);
        meetingService.markLive(room);

        broadcast(room, session.getId(),
                msg("peer-joined").put("id", session.getId()).put("name", name));
        log.info("Meeting {}: {} joined ({} in room)", room, session.getId(), members.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String room = attr(session, "room");
        JsonNode node = objectMapper.readTree(message.getPayload());
        String type = node.path("type").asText("");

        switch (type) {
            case "offer", "answer", "ice" -> {
                // direct relay to one peer, stamped with the sender id
                String to = node.path("to").asText("");
                WebSocketSession target = rooms.getOrDefault(room, Map.of()).get(to);
                if (target != null && target.isOpen()) {
                    ObjectNode out = ((ObjectNode) node)
                            .put("from", session.getId())
                            .put("fromName", attr(session, "name"));
                    send(target, out);
                }
            }
            case "media-state" -> {
                ObjectNode out = ((ObjectNode) node).put("from", session.getId());
                broadcast(room, session.getId(), out);
            }
            case "end-meeting" -> {
                // host pressed End — everyone gets kicked politely
                broadcast(room, null, msg("meeting-ended"));
            }
            default -> { /* ignore unknown types */ }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String room = attr(session, "room");
        Map<String, WebSocketSession> members = rooms.get(room);
        if (members == null) return;
        members.remove(session.getId());
        if (members.isEmpty()) {
            rooms.remove(room);
        } else {
            broadcast(room, null, msg("peer-left").put("id", session.getId()));
        }
        log.info("Meeting {}: {} left", room, session.getId());
    }

    /* ------------------------------------------------------------ helpers */

    private void broadcast(String room, String exceptId, ObjectNode payload) {
        rooms.getOrDefault(room, Map.of()).forEach((peerId, peer) -> {
            if (!peerId.equals(exceptId)) send(peer, payload);
        });
    }

    private void send(WebSocketSession session, ObjectNode payload) {
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload.toString()));
                }
            }
        } catch (Exception e) {
            log.debug("WS send failed: {}", e.getMessage());
        }
    }

    private ObjectNode msg(String type) {
        return objectMapper.createObjectNode().put("type", type);
    }

    private static String attr(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        return value == null ? "" : value.toString();
    }
}
