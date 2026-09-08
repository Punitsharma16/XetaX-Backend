package com.xetax.crm.meeting.ws;

import com.xetax.crm.meeting.service.MeetingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

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
        String name = params.getFirst("name");

        if (room == null || token == null || !meetingService.canJoin(room, token)) {
            return false;
        }
        attributes.put("room", room);
        attributes.put("name", name == null || name.isBlank() ? "Guest" : name);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
