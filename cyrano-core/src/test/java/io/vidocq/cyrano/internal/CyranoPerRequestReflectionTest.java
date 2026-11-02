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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** What a client call must not redo by reflection on every request. */
class CyranoPerRequestReflectionTest {

    private final RecordingHttpServer server = new RecordingHttpServer();

    @BeforeAll
    static void installRuntimeDelegate() {
        TestRuntimeDelegate.install();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** Counts its instances. */
    public static final class CountingFactory implements ClientHeadersFactory {
        static final AtomicInteger CREATED = new AtomicInteger();

        public CountingFactory() {
            CREATED.incrementAndGet();
        }

        @Override
        public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incoming,
                                                     MultivaluedMap<String, String> outgoing) {
            MultivaluedMap<String, String> out = new MultivaluedHashMap<>(outgoing);
            out.putSingle("X-Counted", "yes");
            return out;
        }
    }

    @Path("/api")
    @RegisterClientHeaders(CountingFactory.class)
    public interface CountedApi {
        @GET
        String get();
    }

    @Test
    void theClientHeadersFactoryIsCreatedOncePerClient() {
        server.respond(200, "ok");
        CountedApi client = RestClientBuilder.newBuilder().baseUri(server.baseUri()).build(CountedApi.class);
        int before = CountingFactory.CREATED.get();

        client.get();
        client.get();
        client.get();

        assertEquals(1, CountingFactory.CREATED.get() - before);
        assertEquals("yes", server.last().header("X-Counted"));
    }

    /** A private field without accessor: JSON-B's default visibility leaves it alone. */
    public static final class Reading {
        public String name;
        private String secret;

        public Reading() {
            // JSON-B needs a no-arg constructor
        }

        String secret() {
            return secret;
        }
    }

    @Path("/api")
    public interface ReadingApi {
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        Reading get();
    }

    @Test
    void aPrivateFieldWithoutAccessorIsNotFilledBehindJsonB() {
        server.respond(200, "{\"name\":\"n\",\"secret\":\"s\"}").contentType(MediaType.APPLICATION_JSON);
        ReadingApi client = RestClientBuilder.newBuilder().baseUri(server.baseUri()).build(ReadingApi.class);

        Reading reading = client.get();

        assertEquals("n", reading.name);
        assertNull(reading.secret());
    }
}
