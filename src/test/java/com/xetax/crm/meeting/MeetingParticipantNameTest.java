package com.xetax.crm.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;

import com.xetax.crm.meeting.service.MeetingService;
import com.xetax.crm.meeting.ws.MeetingHandshakeInterceptor;

/**
 * The name a person types before joining travels in the signalling URL's
 * query, so it arrives percent-encoded. Relayed on as it arrived, everyone
 * else in the call saw "Asha%20Rao" on her tile.
 */
class MeetingParticipantNameTest {

    private Map<String, Object> handshake(String query) {
        MeetingService meetings = mock(MeetingService.class);
        when(meetings.canJoin(anyString(), anyString())).thenReturn(true);

        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("wss://api.xetacrm.pro/ws/meeting?" + query));

        Map<String, Object> attributes = new HashMap<>();
        boolean allowed = new MeetingHandshakeInterceptor(meetings)
                .beforeHandshake(request, null, null, attributes);

        assertThat(allowed).isTrue();
        return attributes;
    }

    @Test
    @DisplayName("a name with a space arrives readable")
    void decodesSpaces() {
        assertThat(handshake("room=m6u-ghgg-v9u&t=abc123&name=TEST%20QA%20Host").get("name"))
                .isEqualTo("TEST QA Host");
    }

    @Test
    @DisplayName("accented and non-Latin names survive")
    void decodesUtf8() {
        assertThat(handshake("room=r&t=abc123&name=Asha%20R%C3%A3o").get("name"))
                .isEqualTo("Asha Rão");
        assertThat(handshake("room=r&t=abc123&name=%E0%A4%AA%E0%A5%81%E0%A4%A8%E0%A5%80%E0%A4%A4").get("name"))
                .isEqualTo("पुनीत");
    }

    @Test
    @DisplayName("a plain name is left exactly as it was")
    void leavesPlainNamesAlone() {
        assertThat(handshake("room=r&t=abc123&name=Punit").get("name")).isEqualTo("Punit");
    }

    @Test
    @DisplayName("nobody without a name is still somebody")
    void fallsBackToGuest() {
        assertThat(handshake("room=r&t=abc123").get("name")).isEqualTo("Guest");
        assertThat(handshake("room=r&t=abc123&name=").get("name")).isEqualTo("Guest");
    }

    @Test
    @DisplayName("a percent sign in a name is a percent sign, not an escape")
    void decodesLiteralPercent() {
        // A genuinely malformed escape never reaches this code — the URI
        // parser rejects it at the handshake — so the case worth covering is
        // the one that does arrive: a name that legitimately contains "%".
        assertThat(handshake("room=r&t=abc123&name=100%25%20club").get("name"))
                .isEqualTo("100% club");
    }

    @Test
    @DisplayName("the room code is untouched")
    void keepsTheRoomCode() {
        assertThat(handshake("room=m6u-ghgg-v9u&t=abc123&name=X").get("room")).isEqualTo("m6u-ghgg-v9u");
    }
}
