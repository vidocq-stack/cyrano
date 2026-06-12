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
package io.vidocq.cyrano.cdi.internal;

import com.sun.net.httpserver.HttpServer;
import io.vidocq.cyrano.internal.gen.ClientProxyRegistry;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CG-01 D6 proof: the CDI synthetic bean goes through {@code RestClientBuilder},
 * so {@code @Inject @RestClient} inherits the generated-first resolution chain —
 * the injected proxy is the {@code $$CyranoClient} class, with zero runtime
 * proxy generation.
 */
class CyranoCdiGeneratedClientTest {

    @Dependent
    public static class Consumer {

        @Inject
        @RestClient
        GeneratedPingApi api;
    }

    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", GeneratedPingApi.PORT), 0);
        server.createContext("/gping/", exchange -> {
            String name = exchange.getRequestURI().getPath().substring("/gping/".length());
            byte[] body = ("hello " + name).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        ClientProxyRegistry.resetForTests();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("CG-01 — @Inject @RestClient is served by the generated tier through the BCE creator")
    void injectedClient_usesGeneratedTier_notRuntimeGeneration() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(CyranoRestClientCdiExtension.class)
                .addBeanClass(GeneratedPingApi.class)
                .addBeanClass(Consumer.class)
                .build()) {
            Consumer consumer = container.select(Consumer.class);
            assertNotNull(consumer.api, "@Inject @RestClient must be wired");

            assertInstanceOf(GeneratedPingApi$$CyranoClient.class, consumer.api,
                    "CDI injection must resolve the generated client, not a runtime Cyrano$ proxy");
            assertEquals("hello Vidocq", consumer.api.greet("Vidocq"));

            assertEquals(0, ClientProxyRegistry.runtimeGeneratedHits(),
                    "Runtime generation must stay untouched when a generated client exists");
            assertTrue(ClientProxyRegistry.serviceLoaderHits() + ClientProxyRegistry.preGeneratedHits() >= 1,
                    "The generated tier must have served the resolution");
        }
    }
}
