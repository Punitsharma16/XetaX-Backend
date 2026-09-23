package com.xetax.crm.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The panel reads these strings with {@code new Date(...)}, which takes a
 * date-time with no zone on it as the reader's own local time. That is why an
 * IST user saw every instant 5.5 hours behind, and why what goes out now says
 * which zone it is in.
 */
class TimestampZoneConfigTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    @DisplayName("an instant goes out marked as UTC")
    void marksInstantsAsUtc() {
        LocalDateTime storedByTheServer = LocalDateTime.of(2026, 9, 22, 11, 35, 24);

        assertThat(TimestampZoneConfig.toUtcText(storedByTheServer))
                .isEqualTo("2026-09-22T11:35:24Z");
    }

    @Test
    @DisplayName("an IST reader now sees the hour the thing actually happened")
    void readsBackAsTheRightWallClockInIst() {
        // 11:35 UTC is 5:05 pm in Delhi. Before the zone was written out, the
        // panel drew this as 11:35 am.
        String sent = TimestampZoneConfig.toUtcText(LocalDateTime.of(2026, 9, 22, 11, 35, 24));

        ZonedDateTime asTheReaderSeesIt = java.time.OffsetDateTime.parse(sent).atZoneSameInstant(IST);

        assertThat(asTheReaderSeesIt.toLocalTime()).isEqualTo(LocalTime.of(17, 5, 24));
        assertThat(asTheReaderSeesIt.toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 22));
    }

    @Test
    @DisplayName("sub-second precision survives the trip")
    void keepsNanoseconds() {
        LocalDateTime withNanos = LocalDateTime.of(2026, 9, 22, 11, 35, 24, 976_909_514);

        assertThat(TimestampZoneConfig.toUtcText(withNanos))
                .isEqualTo("2026-09-22T11:35:24.976909514Z");
    }

    @Test
    @DisplayName("a client may send UTC, an offset, or neither")
    void acceptsEveryShapeComingIn() {
        LocalDateTime utcNoon = LocalDateTime.of(2026, 9, 22, 12, 0);

        // What the panel sends now: a real instant.
        assertThat(TimestampZoneConfig.fromText("2026-09-22T12:00:00Z")).isEqualTo(utcNoon);
        // The same instant written from India.
        assertThat(TimestampZoneConfig.fromText("2026-09-22T17:30:00+05:30")).isEqualTo(utcNoon);
        assertThat(TimestampZoneConfig.fromText("2026-09-22T17:30:00+0530")).isEqualTo(utcNoon);
        // An older caller with no zone at all — taken as UTC, exactly as before.
        assertThat(TimestampZoneConfig.fromText("2026-09-22T12:00:00")).isEqualTo(utcNoon);
        assertThat(TimestampZoneConfig.fromText("2026-09-22T12:00")).isEqualTo(utcNoon);
    }

    @Test
    @DisplayName("a round trip changes nothing")
    void roundTrips() {
        LocalDateTime original = LocalDateTime.of(2026, 9, 22, 11, 35, 24, 123_000_000);

        assertThat(TimestampZoneConfig.fromText(TimestampZoneConfig.toUtcText(original)))
                .isEqualTo(original);
    }

    @Test
    @DisplayName("nothing in means nothing out")
    void handlesBlanks() {
        assertThat(TimestampZoneConfig.fromText(null)).isNull();
        assertThat(TimestampZoneConfig.fromText("  ")).isNull();
    }
}
