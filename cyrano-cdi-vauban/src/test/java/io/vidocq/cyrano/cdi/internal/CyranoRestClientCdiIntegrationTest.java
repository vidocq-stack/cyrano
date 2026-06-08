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
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * CDI Vauban integration tests — demonstrates end-to-end
 * {@code @Inject @RestClient} with JDK {@link HttpServer} inline server.
 *
 * <p>Spec MicroProfile Rest Client 4.0 §6.1/§6.2: "Implements of this API
 * must support the @Inject and @RestClient annotations as a means to inject a
 * proxy of the rest client interface into a CDI bean. »</p>
 *
 * <p>Bootstrap: {@code VaubanContainer.builder().addBeanClass(...)}, executed
 * on the classpath ({@code useModulePath=false}). The BCE
 * {@link CyranoRestClientCdiExtension} is discovered via
 * {@code META-INF/services/...BuildCompatibleExtension} on the test classpath
 * (target/classes/META-INF/services/...).</p>
 */
class CyranoRestClientCdiIntegrationTest {

    // -----------------------------------------------------------------------
    //Beans / test client interface
    // -----------------------------------------------------------------------

    /**
     * Annotated test interface {@link RegisterRestClient}. {@code baseUri}
     * tip on fixed {@link TestPort#SERVER_PORT} — Java annotation
     * requires constant compile-time member values. The server
     * JDK {@link HttpServer} is linked to this same port in
     * {@link #startServer()}.
     */
    @RegisterRestClient(baseUri = "http://127.0.0.1:" + TestPort.SERVER_PORT)
    @Path("/ping")
    public interface PingApi {

        @GET
        @Path("/{name}")
        @Produces(MediaType.TEXT_PLAIN)
        String greet(@PathParam("name") String name);
    }

    /** Standard CDI consumer — {@code @Inject @RestClient} injection is what the BCE must wire. */
    @Dependent
    public static class PingConsumer {

        @Inject
        @RestClient
        PingApi pingApi;
    }

    /**
     * Second consumer — demonstrates insulation between injected instances:
     * two {@code @Dependent} beans each receive their proxy (but
     * functionally equivalent since the URI base is identical).
     */
    @Dependent
    public static class AnotherPingConsumer {

        @Inject
        @RestClient
        PingApi pingApi;
    }

    // -----------------------------------------------------------------------
    // Fixture: JDK HTTP server on fixed port TestPort.SERVER_PORT
    // -----------------------------------------------------------------------

    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", TestPort.SERVER_PORT), 0);
        server.createContext("/ping/", exchange -> {
            String path = exchange.getRequestURI().getPath();           // /ping/Antoine
            String name = path.substring("/ping/".length());
            byte[] body = ("hello " + name).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("§6.1+§6.2 — @Inject @RestClient PingApi: BCE synthesizes a bean calling the real server")
    void inject_rest_client_invokes_real_http_server_spec_section6_1() {
        try (VaubanContainer container = buildContainer(PingApi.class, PingConsumer.class)) {
            PingConsumer consumer = container.select(PingConsumer.class);
            assertNotNull(consumer, "PingConsumer bean must be resolved by Vauban");
            assertNotNull(consumer.pingApi, "@Inject @RestClient PingApi must be wired by Cyrano BCE");

            String response = consumer.pingApi.greet("Antoine");
            assertEquals("hello Antoine", response);
        }
    }

    @Test
    @DisplayName("§6.2 — each @Inject @RestClient receives a working proxy (same URL)")
    void second_consumer_also_receives_a_working_proxy_spec_section6_2() {
        try (VaubanContainer container = buildContainer(
                PingApi.class, PingConsumer.class, AnotherPingConsumer.class)) {
            AnotherPingConsumer second = container.select(AnotherPingConsumer.class);
            assertNotNull(second);
            assertNotNull(second.pingApi, "Second consumer: @Inject @RestClient must also be wired");
            assertEquals("hello Vauban", second.pingApi.greet("Vauban"));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static VaubanContainer buildContainer(Class<?>... beanClasses) {
        var builder = VaubanContainer.builder()
                .addBeanClass(CyranoRestClientCdiExtension.class);
        for (Class<?> c : beanClasses) {
            builder.addBeanClass(c);
        }
        return builder.build();
    }
}




