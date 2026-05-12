/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
 * Tests d'intégration CDI Vauban — démontre le bout-en-bout
 * {@code @Inject @RestClient} avec un serveur JDK {@link HttpServer} inline.
 *
 * <p>Spec MicroProfile Rest Client 4.0 §6.1/§6.2 : « Implementations of this API
 * must support the @Inject and @RestClient annotations as a means to inject a
 * proxy of the rest client interface into a CDI bean. »</p>
 *
 * <p>Bootstrap : {@code VaubanContainer.builder().addBeanClass(...)}, exécution
 * sur classpath ({@code useModulePath=false}). La BCE
 * {@link CyranoRestClientCdiExtension} est découverte via
 * {@code META-INF/services/...BuildCompatibleExtension} sur le classpath de
 * test (target/classes/META-INF/services/...).</p>
 */
class CyranoRestClientCdiIntegrationTest {

    // -----------------------------------------------------------------------
    // Beans / interface client de test
    // -----------------------------------------------------------------------

    /**
     * Interface de test annotée {@link RegisterRestClient}. La {@code baseUri}
     * pointe sur le {@link TestPort#SERVER_PORT} fixe — l'annotation Java
     * exige des valeurs de membre constantes en compile-time. Le serveur
     * JDK {@link HttpServer} est lié à ce même port dans
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

    /** Consommateur CDI standard — l'injection {@code @Inject @RestClient} est ce que la BCE doit câbler. */
    @Dependent
    public static class PingConsumer {

        @Inject
        @RestClient
        PingApi pingApi;
    }

    /**
     * Second consommateur — démontre l'isolation entre instances injectées :
     * deux beans {@code @Dependent} reçoivent chacun leur proxy (mais
     * fonctionnellement équivalents puisque la base URI est identique).
     */
    @Dependent
    public static class AnotherPingConsumer {

        @Inject
        @RestClient
        PingApi pingApi;
    }

    // -----------------------------------------------------------------------
    // Fixture : serveur HTTP JDK sur le port fixe TestPort.SERVER_PORT
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
    @DisplayName("§6.1+§6.2 — @Inject @RestClient PingApi : la BCE synthétise un bean appelant le serveur réel")
    void inject_rest_client_invokes_real_http_server_spec_section6_1() {
        try (VaubanContainer container = buildContainer(PingApi.class, PingConsumer.class)) {
            PingConsumer consumer = container.select(PingConsumer.class);
            assertNotNull(consumer, "Le bean PingConsumer doit être résolu par Vauban");
            assertNotNull(consumer.pingApi, "@Inject @RestClient PingApi doit être câblé par la BCE Cyrano");

            String response = consumer.pingApi.greet("Antoine");
            assertEquals("hello Antoine", response);
        }
    }

    @Test
    @DisplayName("§6.2 — chaque @Inject @RestClient reçoit un proxy fonctionnel (même URL)")
    void second_consumer_also_receives_a_working_proxy_spec_section6_2() {
        try (VaubanContainer container = buildContainer(
                PingApi.class, PingConsumer.class, AnotherPingConsumer.class)) {
            AnotherPingConsumer second = container.select(AnotherPingConsumer.class);
            assertNotNull(second);
            assertNotNull(second.pingApi, "Deuxième consommateur : @Inject @RestClient doit aussi être câblé");
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




