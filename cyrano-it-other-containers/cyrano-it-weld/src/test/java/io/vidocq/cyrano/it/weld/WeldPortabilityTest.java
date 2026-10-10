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
package io.vidocq.cyrano.it.weld;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Cyrano jars, unchanged, under Weld SE on a class path (vidocq-workspace#15, cyrano#34): the
 * build compatible extension registers a synthetic bean per {@code @RegisterRestClient} interface,
 * the base URI comes from MicroProfile Config, and both proxy sources work — the one the processor
 * generated, and the run-time fallback for an interface it never saw. Nothing generated for Vauban
 * is used.
 */
class WeldPortabilityTest {

    private static HttpServer stub;
    private static WeldContainer container;

    @BeforeAll
    static void start() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/greet/", exchange -> {
            String name = exchange.getRequestURI().getPath().substring("/greet/".length());
            String message = "hello " + name;
            byte[] body = ("{\"message\":\"" + message + "\",\"length\":" + message.length() + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        // MP Rest Client 4.0 §5: <configKey>/mp-rest/url, read through MicroProfile Config (Ravel).
        System.setProperty("stub/mp-rest/url", baseUri().toString());
        container = new Weld().initialize();
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.close();
        }
        if (stub != null) {
            stub.stop(0);
        }
        System.clearProperty("stub/mp-rest/url");
    }

    private static URI baseUri() {
        return URI.create("http://127.0.0.1:" + stub.getAddress().getPort());
    }

    @Test
    void vaubanIsNotOnTheClassPath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.vidocq.vauban.core.container.VaubanContainer"));
    }

    @Test
    void injectedRestClientCallsTheStub() {
        Greeting greeting = container.select(Caller.class).get().greet("weld");
        assertEquals("hello weld", greeting.message);
        assertEquals(10, greeting.length);
    }

    @Test
    void injectedRestClientUsesTheProcessorGeneratedProxy() {
        assertTrue(container.select(Caller.class).get().clientClassName().contains("$$CyranoClient"),
                container.select(Caller.class).get().clientClassName());
    }

    @Test
    void programmaticClientUsesTheRunTimeFallback() {
        PlainClient client = RestClientBuilder.newBuilder().baseUri(baseUri()).build(PlainClient.class);

        assertEquals("hello fallback", client.greet("fallback").message);
        assertFalse(client.getClass().getName().contains("$$CyranoClient"), client.getClass().getName());
    }
}
