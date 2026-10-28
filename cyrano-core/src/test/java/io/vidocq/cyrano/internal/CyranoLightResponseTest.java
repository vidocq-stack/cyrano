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
package io.vidocq.cyrano.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CyranoLightResponse}: the delegate-free {@code Response} handed to
 * {@code ResponseExceptionMapper}s and to the default mapper (MP Rest Client 4.0 §8).
 */
class CyranoLightResponseTest {

    private static final String JSON_OBJECT = "{\"name\":\"a.txt\"}";

    /** Reads the body as an upper-cased string — a provider registered on the client. */
    public static final class UpperReader implements MessageBodyReader<Upper> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Upper.class;
        }

        @Override
        public Upper readFrom(Class<Upper> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                              MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            return new Upper(new String(entityStream.readAllBytes(), StandardCharsets.UTF_8).toUpperCase());
        }
    }

    /** A reader that fails with an I/O error. */
    public static final class FailingReader implements MessageBodyReader<Upper> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return true;
        }

        @Override
        public Upper readFrom(Class<Upper> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                              MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            throw new IOException("broken stream");
        }
    }

    /** Wraps the value produced by the reader, proving that the interceptor chain ran. */
    public static final class WrappingInterceptor implements ReaderInterceptor {
        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
            Upper read = (Upper) context.proceed();
            return new Upper("[" + read.value() + "]");
        }
    }

    public record Upper(String value) {
    }

    private static CyranoLightResponse response(String body, CyranoClientConfiguration configuration) {
        CyranoClientResponseContext ctx = CyranoClientResponseContext.fromAbort(StubResponse.ofString(200, body));
        return CyranoLightResponse.of(ctx, body, configuration);
    }

    private static CyranoClientConfiguration configurationWith(Object... providers) {
        CyranoClientConfiguration configuration = new CyranoClientConfiguration();
        for (Object provider : providers) {
            configuration.registerProvider(provider, Map.of());
        }
        return configuration;
    }

    @Test
    void readEntity_returns_the_body_as_string_without_configuration() {
        assertEquals("plain", response("plain", null).readEntity(String.class));
    }

    @Test
    void readEntity_parses_a_json_object_and_a_json_array_without_reader() {
        JsonObject object = response(JSON_OBJECT, null).readEntity(JsonObject.class);
        assertEquals("a.txt", object.getString("name"));

        JsonArray array = response("[" + JSON_OBJECT + "]", configurationWith()).readEntity(JsonArray.class);
        assertEquals(1, array.size());
        assertEquals("a.txt", array.getJsonObject(0).getString("name"));
    }

    @Test
    void readEntity_of_an_unknown_type_without_reader_is_unsupported() {
        CyranoLightResponse response = response("x", configurationWith());
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> response.readEntity(Upper.class));
        assertTrue(e.getMessage().contains("Upper"), e.getMessage());
    }

    @Test
    void readEntity_uses_a_registered_reader_spec_section4_2() {
        Upper upper = response("abc", configurationWith(new UpperReader())).readEntity(Upper.class);
        assertEquals("ABC", upper.value());
    }

    @Test
    void readEntity_runs_registered_reader_interceptors_around_the_reader() {
        CyranoClientConfiguration configuration = configurationWith(new UpperReader(), new WrappingInterceptor());
        assertEquals("[ABC]", response("abc", configuration).readEntity(Upper.class).value());
    }

    @Test
    void readEntity_wraps_a_reader_io_failure_in_processing_exception() {
        CyranoLightResponse response = response("abc", configurationWith(new FailingReader()));
        ProcessingException e = assertThrows(ProcessingException.class, () -> response.readEntity(Upper.class));
        assertInstanceOf(IOException.class, e.getCause());
    }

    @Test
    void status_only_response_has_no_entity() {
        CyranoLightResponse response = CyranoLightResponse.ofStatus(404);
        assertEquals(404, response.getStatus());
        assertEquals(404, response.getStatusInfo().getStatusCode());
        assertFalse(response.hasEntity());
        assertEquals(-1, response.getLength());
        assertNull(response.getEntity());
        assertNull(response.getLocation());
        assertNull(response.getHeaderString("Content-Type"));
    }

    @Test
    void header_lookup_is_case_insensitive_and_joins_values() {
        StubResponse stub = StubResponse.ofString(201, "body");
        stub.getStringHeaders().add("X-Trace", "a");
        stub.getStringHeaders().add("X-Trace", "b");
        stub.getStringHeaders().add("Location", "http://localhost/items/1");
        CyranoClientResponseContext ctx = CyranoClientResponseContext.fromAbort(stub);
        CyranoLightResponse response = CyranoLightResponse.of(ctx, "body");

        assertEquals("a,b", response.getHeaderString("x-trace"));
        assertEquals("a,b", response.getHeaderString("X-Trace"));
        assertEquals(URI.create("http://localhost/items/1"), response.getLocation());
        assertEquals(2, response.getHeaders().get("X-Trace").size());
        assertEquals(response.getHeaders(), response.getMetadata());
        assertEquals(2, response.getStringHeaders().get("X-Trace").size());
        assertTrue(response.hasEntity());
        assertEquals(4, response.getLength());
        assertEquals("body", response.getEntity());
    }

    @Test
    void unsupported_parts_of_the_response_api_report_their_absence() {
        CyranoLightResponse response = response("x", null);
        assertEquals(MediaType.WILDCARD_TYPE, response.getMediaType());
        assertNull(response.getLanguage());
        assertNull(response.getEntityTag());
        assertNull(response.getDate());
        assertNull(response.getLastModified());
        assertNull(response.getLink("next"));
        assertFalse(response.hasLink("next"));
        assertTrue(response.getLinks().isEmpty());
        assertTrue(response.getAllowedMethods().isEmpty());
        assertTrue(response.getCookies().isEmpty());
        assertFalse(response.bufferEntity());
        assertThrows(UnsupportedOperationException.class, () -> response.getLinkBuilder("next"));
        assertThrows(UnsupportedOperationException.class,
                () -> response.readEntity(String.class, new Annotation[0]));
    }
}
