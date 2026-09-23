package com.xetax.crm.meeting.ws;

import com.xetax.crm.meeting.service.MeetingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Gate on the signaling socket: the upgrade only succeeds when the room code
 * + token pair is valid and the meeting is joinable. No JWT here — guests
 * are anonymous; the token in the shared link IS the credential.
 */
@Component
@RequiredArgsConstructor
public class MeetingHandshakeInterceptor implements HandshakeInterceptor {

    private final MeetingService meetingService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        var params = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        String room = params.getFirst("room");
        String token = params.getFirst("t");
        // The query arrives percent-encoded, so a name with a space in it
        // reached everyone else in the call as "Asha%20Rao" — that string was
        // relayed to the other browsers and drawn on their tiles as-is.
        // Room codes and tokens are plain hex, so only the name needs this.
        String name = decode(params.getFirst("name"));

        if (room == null || token == null || !meetingService.canJoin(room, token)) {
            return false;
        }
        attributes.put("room", room);
        attributes.put("name", name == null || name.isBlank() ? "Guest" : name);
        return true;
    }

    private static String decode(String value) {
        if (value == null) return null;
        try {
            return UriUtils.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // A malformed escape is the caller's problem, not a reason to
            // refuse the handshake — show whatever they sent.
            return value;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
