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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A client interface may inherit its methods from a generic super-interface it parameterizes, a common way to share
 * a CRUD contract: {@code interface EchoClient extends Echo<String>}. Its methods keep their erased JVM signature
 * ({@code post(Object)}), and a type variable in their return type is the type the client interface binds it to.
 * The generated client and the runtime one must both serve it.
 */
class GenericSuperInterfaceTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = (exchange.getRequestMethod() + ":" + exchange.getRequestURI().getPath()
                    + (requestBody.isEmpty() ? "" : ":" + requestBody)).getBytes(StandardCharsets.UTF_8);
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aClientInheritsTheMethodsOfAGenericSuperInterface(boolean processor) throws Exception {
        var echo = ProcessorTestHarness.writeSource(tempDir, "t/Echo.java", """
                package t;
                import jakarta.ws.rs.*;
                public interface Echo<T> {
                    @POST @Consumes("text/plain") @Produces("text/plain")
                    String post(T body);

                    @GET @Path("/{id}") @Produces("text/plain")
                    T get(@PathParam("id") String id);
                }
                """);
        var client = ProcessorTestHarness.writeSource(tempDir, "t/EchoClient.java", """
                package t;
                import jakarta.ws.rs.Path;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
                @RegisterRestClient
                @Path("/echo")
                public interface EchoClient extends Echo<String> {
                }
                """);
        var compilation = processor
                ? ProcessorTestHarness.compileWithProcessor(tempDir, echo, client)
                : ProcessorTestHarness.compileWithoutProcessor(tempDir, echo, client);
        Class<?> iface = compilation.loader().loadClass("t.EchoClient");

        @SuppressWarnings("unchecked")
        Object proxy = RestClientBuilder.newBuilder()
                .baseUri(URI.create("http://127.0.0.1:" + port))
                .build((Class<Object>) iface);

        assertEquals("POST:/echo:hello", iface.getMethod("post", Object.class).invoke(proxy, "hello"));
        assertEquals("GET:/echo/7", iface.getMethod("get", String.class).invoke(proxy, "7"));
        assertEquals(processor ? 0 : 1, ClientProxyRegistry.runtimeGeneratedHits(),
                processor ? "the generated client serves it" : "the runtime generator serves it");
    }

    /**
     * Redeclaring an inherited method with the bound type makes javac add a bridge to the client interface: a default
     * {@code Object get(String)} carrying the same annotations, which calls the redeclared method. Only abstract
     * methods are client methods, so a call through the super-interface goes through the bridge to the redeclared one.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aCallThroughTheSuperInterfaceReachesTheRedeclaredMethod(boolean processor) throws Exception {
        var echo = ProcessorTestHarness.writeSource(tempDir, "t/Echo.java", """
                package t;
                import jakarta.ws.rs.*;
                public interface Echo<T> {
                    @GET @Path("/{id}") @Produces("text/plain")
                    T get(@PathParam("id") String id);
                }
                """);
        var client = ProcessorTestHarness.writeSource(tempDir, "t/EchoClient.java", """
                package t;
                import jakarta.ws.rs.*;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
                @RegisterRestClient
                @Path("/echo")
                public interface EchoClient extends Echo<String> {
                    @Override
                    @GET @Path("/{id}") @Produces("text/plain")
                    String get(@PathParam("id") String id);
                }
                """);
        var compilation = processor
                ? ProcessorTestHarness.compileWithProcessor(tempDir, echo, client)
                : ProcessorTestHarness.compileWithoutProcessor(tempDir, echo, client);
        Class<?> iface = compilation.loader().loadClass("t.EchoClient");

        @SuppressWarnings("unchecked")
        Object proxy = RestClientBuilder.newBuilder()
                .baseUri(URI.create("http://127.0.0.1:" + port))
                .build((Class<Object>) iface);

        assertEquals("GET:/echo/7", iface.getMethod("get", String.class).invoke(proxy, "7"));
        assertEquals("GET:/echo/8", compilation.loader().loadClass("t.Echo")
                .getMethod("get", String.class).invoke(proxy, "8"));
    }
}
