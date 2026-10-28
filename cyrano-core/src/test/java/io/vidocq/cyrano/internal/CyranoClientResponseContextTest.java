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

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CyranoClientResponseContext}: the {@code ClientResponseContext} handed to the
 * {@code ClientResponseFilter}s (MP Rest Client 4.0 §4.2, Jakarta REST §6.3).
 */
class CyranoClientResponseContextTest {

    @BeforeAll
    static void installRuntimeDelegate() {
        TestRuntimeDelegate.install();
    }

    /** A response whose entity is not a string — exercises the entity-to-stream conversion. */
    private static Response withEntity(Object entity) {
        return StubResponse.ofEntity(200, entity);
    }

    @Test
    void abort_response_without_entity_has_an_empty_stream() {
        CyranoClientResponseContext ctx = CyranoClientResponseContext.fromAbort(StubResponse.ofString(204, null));

        assertEquals(204, ctx.getStatus());
        assertFalse(ctx.hasEntity());
        assertEquals("", ctx.readBodyAsString());
    }

    @Test
    void abort_response_entity_is_converted_to_a_stream() {
        assertEquals("bytes", CyranoClientResponseContext.fromAbort(
                withEntity("bytes".getBytes(StandardCharsets.UTF_8))).readBodyAsString());
        assertEquals("stream", CyranoClientResponseContext.fromAbort(
                withEntity(new ByteArrayInputStream("stream".getBytes(StandardCharsets.UTF_8)))).readBodyAsString());
        assertEquals("42", CyranoClientResponseContext.fromAbort(withEntity(42)).readBodyAsString());
    }

    @Test
    void status_and_status_info_stay_consistent() {
        CyranoClientResponseContext ctx = CyranoClientResponseContext.fromAbort(StubResponse.ofString(200, "x"));

        ctx.setStatus(404);
        assertEquals(Response.Status.NOT_FOUND, ctx.getStatusInfo());

        ctx.setStatusInfo(Response.Status.CREATED);
        assertEquals(201, ctx.getStatus());
    }

    @Test
    void headers_are_looked_up_case_insensitively() {
        StubResponse stub = StubResponse.ofString(200, "x");
        stub.getStringHeaders().add("Allow", "get, post");
        stub.getStringHeaders().add("Content-Language", "fr-FR");
        stub.getStringHeaders().add("Content-Length", "12");
        stub.getStringHeaders().add("Content-Type", "text/plain");
        stub.getStringHeaders().add("Location", "http://localhost/items/1");
        CyranoClientResponseContext ctx = CyranoClientResponseContext.fromAbort(stub);

        assertEquals(Set.of("GET", "POST"), ctx.getAllowedMethods());
        assertEquals(Locale.forLanguageTag("fr-FR"), ctx.getLanguage());
        assertEquals(12, ctx.getLength());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, ctx.getMediaType());
        assertEquals(URI.create("http://localhost/items/1"), ctx.getLocation());
        assertEquals("get, post", ctx.getHeaderString("allow"));
        assertTrue(ctx.containsHeaderString("allow", ",", "post"::equals));
        assertTrue(ctx.containsHeaderString("Allow", null, "get, post"::equals));
        assertFalse(ctx.containsHeaderString("Allow", ",", "put"::equals));
        assertFalse(ctx.containsHeaderString("X-Absent", ",", v -> true));
    }

    @Test
    void absent_or_invalid_headers_give_neutral_values() {
        StubResponse stub = StubResponse.ofString(200, "x");
        stub.getStringHeaders().add("Content-Length", "twelve");
        CyranoClientResponseContext ctx = CyranoClientResponseContext.fromAbort(stub);

        assertEquals(-1, ctx.getLength());
        assertTrue(ctx.getAllowedMethods().isEmpty());
        assertNull(ctx.getLanguage());
        assertNull(ctx.getMediaType());
        assertNull(ctx.getLocation());
        assertNull(ctx.getHeaderString("X-Absent"));
        assertNull(ctx.getDate());
        assertNull(ctx.getEntityTag());
        assertNull(ctx.getLastModified());
        assertNull(ctx.getLink("next"));
        assertNull(ctx.getLinkBuilder("next"));
        assertFalse(ctx.hasLink("next"));
        assertTrue(ctx.getLinks().isEmpty());
        assertTrue(ctx.getCookies().isEmpty());
    }

    @Test
    void entity_stream_can_be_replaced_and_a_failing_stream_has_no_entity() {
        CyranoClientResponseContext ctx = CyranoClientResponseContext.fromAbort(StubResponse.ofString(200, "x"));
        assertTrue(ctx.hasEntity());

        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("closed");
            }

            @Override
            public int available() throws IOException {
                throw new IOException("closed");
            }
        };
        ctx.setEntityStream(failing);

        assertSame(failing, ctx.getEntityStream());
        assertFalse(ctx.hasEntity());
        assertEquals("", ctx.readBodyAsString());

        ctx.setEntityStream(null);
        assertFalse(ctx.hasEntity());
    }
}
