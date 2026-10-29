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

import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A method the client interface inherits from a super-interface is a method of the client interface: what the client
 * interface declares at type level applies to it, and the compute methods its headers name are looked up there.
 */
class InheritedClientMethodTest {

    public interface Base {
        @GET
        String get();
    }

    @Path("/headers")
    @ClientHeaderParam(name = "X-Computed", value = "{computed}")
    @RegisterClientHeaders(Factory.class)
    public interface Client extends Base {
        default String computed() {
            return "from-the-client-interface";
        }
    }

    public static class Factory implements ClientHeadersFactory {
        @Override
        public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incoming,
                                                     MultivaluedMap<String, String> outgoing) {
            MultivaluedMap<String, String> out = new MultivaluedHashMap<>(outgoing);
            out.putSingle("X-Factory", "registered-on-the-client-interface");
            return out;
        }
    }

    private HttpServer server;
    private URI baseUri;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] payload = (ex.getRequestHeaders().getFirst("X-Computed") + "|"
                    + ex.getRequestHeaders().getFirst("X-Factory")).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, payload.length);
            try (var os = ex.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theClientInterfaceHeadersApplyToAnInheritedMethod() {
        Client client = RestClientBuilder.newBuilder().baseUri(baseUri).build(Client.class);

        assertEquals("from-the-client-interface|registered-on-the-client-interface", client.get());
    }
}
