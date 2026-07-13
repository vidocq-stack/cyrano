/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.cyrano.internal.sse;

import io.vidocq.cyrano.internal.CyranoClientConfiguration;
import io.vidocq.cyrano.internal.CyranoRestClientBuilder;
import jakarta.json.JsonObject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.sse.SseEvent;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link InboundSseEventImpl} — MP Rest Client 4.0 §10,
 * {@code InboundSseEvent} contract (jakarta.ws.rs.sse).
 */
class InboundSseEventImplTest {

    /** Hand-written double — no Mockito (repo rule). */
    static final class WeatherEvent {
        String description;
    }

    /** Hand-written MessageBodyReader for {@link WeatherEvent}. */
    static final class WeatherEventReader implements MessageBodyReader<WeatherEvent> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType,
                                  Annotation[] annotations, MediaType mediaType) {
            return type == WeatherEvent.class;
        }

        @Override
        public WeatherEvent readFrom(Class<WeatherEvent> type, Type genericType,
                                     Annotation[] annotations, MediaType mediaType,
                                     MultivaluedMap<String, String> httpHeaders,
                                     InputStream entityStream) {
            String raw = new BufferedReader(new InputStreamReader(entityStream, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            WeatherEvent ev = new WeatherEvent();
            ev.description = "read:" + raw;
            return ev;
        }
    }

    private static SseDataDeserializer deserializer(Object... providers) {
        var builder = new CyranoRestClientBuilder().baseUri(URI.create("http://localhost"));
        for (Object p : providers) {
            builder.register(p);
        }
        return new SseDataDeserializer((CyranoClientConfiguration) builder.getConfiguration());
    }

    private static InboundSseEventImpl event(SseEventParser.SseEventData data, Object... providers) {
        return new InboundSseEventImpl(data, deserializer(providers));
    }

    @Test
    void accessors_expose_name_id_comment_and_string_data() {
        var ev = event(new SseEventParser.SseEventData("weather", "7", "a comment", "sunny", null));
        assertEquals("weather", ev.getName());
        assertEquals("7", ev.getId());
        assertEquals("a comment", ev.getComment());
        assertEquals("sunny", ev.readData());
        assertEquals("sunny", ev.readData(String.class));
        assertFalse(ev.isEmpty());
        assertFalse(ev.isReconnectDelaySet());
        assertEquals(SseEvent.RECONNECT_NOT_SET, ev.getReconnectDelay());
    }

    @Test
    void reconnectDelay_is_exposed_when_retry_field_was_present() {
        var ev = event(new SseEventParser.SseEventData(null, null, null, "x", 3000L));
        assertTrue(ev.isReconnectDelaySet());
        assertEquals(3000L, ev.getReconnectDelay());
    }

    @Test
    void commentOnly_event_is_empty_and_readData_returns_null() {
        var ev = event(new SseEventParser.SseEventData(null, null, "heartbeat", null, null));
        assertTrue(ev.isEmpty());
        assertNull(ev.readData());
        assertNull(ev.readData(String.class));
        assertEquals("heartbeat", ev.getComment());
    }

    @Test
    void readData_jsonObject_uses_jsonp_reader() {
        var ev = event(new SseEventParser.SseEventData(null, null, null,
                "{\"temp\":21,\"sky\":\"clear\"}", null));
        JsonObject json = ev.readData(JsonObject.class);
        assertEquals(21, json.getInt("temp"));
        assertEquals("clear", json.getString("sky"));
    }

    @Test
    void readData_customType_uses_registered_messageBodyReader() {
        var ev = event(new SseEventParser.SseEventData(null, null, null, "cloudy", null),
                new WeatherEventReader());
        WeatherEvent weather = ev.readData(WeatherEvent.class);
        assertEquals("read:cloudy", weather.description);
    }

    @Test
    void readData_pojo_falls_back_to_jsonb_when_no_reader_matches() {
        var ev = event(new SseEventParser.SseEventData(null, null, null,
                "{\"description\":\"windy\"}", null));
        WeatherPojo pojo = ev.readData(WeatherPojo.class);
        assertEquals("windy", pojo.getDescription());
    }

    /** Public POJO with accessors so the JSON-B fallback can bind it. */
    public static class WeatherPojo {
        private String description;

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
