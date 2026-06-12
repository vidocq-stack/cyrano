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
package io.vidocq.cyrano.processor;

import com.sun.net.httpserver.HttpServer;
import io.vidocq.cyrano.internal.gen.ClientProxyRegistry;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-chain proof for CG-01: a fixture compiled WITH the processor, driven through the
 * real {@code RestClientBuilder} against an inline JDK HttpServer, must be served by the
 * generated tier — zero runtime proxy generation, zero annotation scanning.
 */
class GeneratedClientEndToEndTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ("echo:" + exchange.getRequestMethod() + ":"
                    + exchange.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        port = server.getAddress().getPort();
        ClientProxyRegistry.resetForTests();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void generatedClient_servesRealHttpCall_withoutRuntimeGeneration() throws Exception {
        var api = ProcessorTestHarness.writeSource(tempDir, "t/EchoApi.java", """
                package t;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;
                import jakarta.ws.rs.Produces;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

                @RegisterRestClient
                @Path("/echo")
                public interface EchoApi {

                    @GET
                    @Path("/{id}")
                    @Produces("text/plain")
                    String get(@PathParam("id") String id);
                }
                """);
        var compilation = ProcessorTestHarness.compileWithProcessor(tempDir, api);
        Class<?> iface = compilation.loader().loadClass("t.EchoApi");

        @SuppressWarnings("unchecked")
        Object client = RestClientBuilder.newBuilder()
                .baseUri(URI.create("http://127.0.0.1:" + port))
                .build((Class<Object>) iface);

        assertEquals("t.EchoApi$$CyranoClient", client.getClass().getName(),
                "The proxy must be the APT-generated class, not a runtime Cyrano$ proxy");

        Object result = iface.getMethod("get", String.class).invoke(client, "42");
        assertEquals("echo:GET:/echo/42", result);

        assertEquals(0, ClientProxyRegistry.runtimeGeneratedHits(),
                "Runtime Class-File generation must stay untouched on the generated path");
        assertEquals(1,
                ClientProxyRegistry.serviceLoaderHits() + ClientProxyRegistry.preGeneratedHits(),
                "Exactly one resolution, served by a generated tier");
    }

    @Test
    void interfaceWithoutGeneratedClient_stillWorksThroughFallback() throws Exception {
        Object client = RestClientBuilder.newBuilder()
                .baseUri(URI.create("http://127.0.0.1:" + port))
                .build(PlainApi.class);
        assertTrue(client.getClass().getName().contains("Cyrano$"),
                "No generated artifact for PlainApi: the runtime generator must serve it");
        assertEquals("echo:GET:/plain", client instanceof PlainApi p ? p.get() : null);
        assertEquals(1, ClientProxyRegistry.runtimeGeneratedHits());
    }

    @jakarta.ws.rs.Path("/plain")
    public interface PlainApi {
        @jakarta.ws.rs.GET
        String get();
    }
}
