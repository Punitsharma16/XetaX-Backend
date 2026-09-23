package com.xetax.crm.config;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.jackson2.autoconfigure.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every timestamp this API sends carries its zone.
 *
 * <p>Each {@link LocalDateTime} in this codebase holds a UTC instant: the
 * server runs in UTC, so {@code LocalDateTime.now()} is UTC and that is what
 * reaches the database. Written out plainly — {@code "2026-09-22T11:35:24"} —
 * a browser reads it as its <em>own</em> local time, which showed an IST user
 * every instant 5.5 hours behind: a chat that came in at 5:05 pm was labelled
 * 11:35 am. Writing {@code "2026-09-22T11:35:24Z"} instead costs one character
 * and makes every clock in the panel right, without the panel having to guess
 * what the string meant.
 *
 * <p>Values somebody picked off a wall clock never pass through here. A
 * booking slot is a {@link java.time.LocalDate} plus a
 * {@link java.time.LocalTime}, and an invoice's issue and due dates are
 * LocalDate, so a 10:30 slot stays 10:30 everywhere on earth — which is the
 * whole reason this is done by type rather than by shifting everything.
 *
 * <p>Coming back in, a string that carries a zone is converted to UTC before
 * it is stored, so a client may send either shape: the panel already sends
 * campaign schedules and task due times as {@code "…Z"}, while older callers
 * send a bare date-time that is taken as UTC as before.
 */
@Configuration
public class TimestampZoneConfig {

    /** Trailing {@code Z}, {@code +05:30} or {@code +0530}. */
    private static final Pattern HAS_ZONE = Pattern.compile("([Zz]|[+-]\\d{2}:?\\d{2})$");

    /** The colon-less offset some clients write, e.g. {@code +0530}. */
    private static final Pattern COMPACT_OFFSET = Pattern.compile("([+-]\\d{2})(\\d{2})$");

    static String toUtcText(LocalDateTime value) {
        return value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static LocalDateTime fromText(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (HAS_ZONE.matcher(text).find()) {
            // ISO parsing wants "+05:30"; RFC-822 style "+0530" arrives often
            // enough to be worth the colon rather than a second parser.
            String iso = COMPACT_OFFSET.matcher(text).replaceFirst("$1:$2");
            return OffsetDateTime.parse(iso).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        }
        return LocalDateTime.parse(text);
    }

    /*
     * Both Jackson generations are on the classpath (see the spring-boot-jackson2
     * note in pom.xml), and which one serves a given response is not something
     * this class should depend on — so both are taught the same rule.
     */

    @Bean
    Jackson2ObjectMapperBuilderCustomizer utcTimestampsForJackson2() {
        return builder -> builder
                .serializerByType(LocalDateTime.class, new Jackson2Serializer())
                .deserializerByType(LocalDateTime.class, new Jackson2Deserializer());
    }

    @Bean
    JsonMapperBuilderCustomizer utcTimestampsForJackson3() {
        return builder -> {
            tools.jackson.databind.module.SimpleModule module =
                    new tools.jackson.databind.module.SimpleModule("utc-timestamps");
            module.addSerializer(LocalDateTime.class, new Jackson3Serializer());
            module.addDeserializer(LocalDateTime.class, new Jackson3Deserializer());
            builder.addModule(module);
        };
    }

    static final class Jackson2Serializer extends com.fasterxml.jackson.databind.JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, com.fasterxml.jackson.core.JsonGenerator gen,
                              com.fasterxml.jackson.databind.SerializerProvider provider)
                throws java.io.IOException {
            gen.writeString(toUtcText(value));
        }
    }

    static final class Jackson2Deserializer extends com.fasterxml.jackson.databind.JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(com.fasterxml.jackson.core.JsonParser p,
                                         com.fasterxml.jackson.databind.DeserializationContext ctxt)
                throws java.io.IOException {
            return fromText(p.getValueAsString());
        }
    }

    static final class Jackson3Serializer extends tools.jackson.databind.ValueSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, tools.jackson.core.JsonGenerator gen,
                              tools.jackson.databind.SerializationContext ctxt) {
            gen.writeString(toUtcText(value));
        }
    }

    static final class Jackson3Deserializer extends tools.jackson.databind.ValueDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(tools.jackson.core.JsonParser p,
                                         tools.jackson.databind.DeserializationContext ctxt) {
            return fromText(p.getValueAsString());
        }
    }
}
