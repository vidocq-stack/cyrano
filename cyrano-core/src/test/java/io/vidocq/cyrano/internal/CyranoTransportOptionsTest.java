/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cyrano.internal;

import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * M4-4 tests — transport options for the builder ({@code followRedirects},
 * {@code readTimeout}) propagated to JDK transport and {@link Response} returned.
 *
 * <p>Spec MicroProfile Rest Client 4.0 §5 "Configuration".</p>
 */
class CyranoTransportOptionsTest {

    @Path("/redirect")
    interface RedirectApi {
        @GET
        Response execute();
    }

    @Path("/slow")
    interface SlowApi {
        @GET
        String execute();
    }

    private HttpServer server;
    private URI baseUri;
    private final AtomicInteger redirectCount = new AtomicInteger();
    private final AtomicInteger redirectedCount = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] payload;
            int status;
            if ("/redirect".equals(path)) {
                redirectCount.incrementAndGet();
                exchange.getResponseHeaders().add("Location", baseUri.resolve("/redirected").toString());
                payload = "did not follow redirect".getBytes(StandardCharsets.UTF_8);
                status = 302;
            } else if ("/redirected".equals(path)) {
                redirectedCount.incrementAndGet();
                payload = "followed redirect".getBytes(StandardCharsets.UTF_8);
                status = 200;
            } else if ("/slow".equals(path)) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                payload = "slow-body".getBytes(StandardCharsets.UTF_8);
                status = 200;
            } else {
                payload = "not-found".getBytes(StandardCharsets.UTF_8);
                status = 404;
            }
            exchange.sendResponseHeaders(status, payload.length);
            try (var os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void followRedirects_false_by_default_returns_redirect_response_with_location_header() {
        RedirectApi client = new CyranoRestClientBuilder()
                .baseUri(baseUri)
                .build(RedirectApi.class);

        try (Response response = client.execute()) {
            assertEquals(302, response.getStatus());
            assertEquals(baseUri.resolve("/redirected"), response.getLocation());
            assertEquals(baseUri.resolve("/redirected").toString(), response.getHeaderString("Location"));
            assertEquals("did not follow redirect", response.readEntity(String.class));
        }
        assertEquals(1, redirectCount.get());
        assertEquals(0, redirectedCount.get());
    }

    @Test
    void followRedirects_true_follows_redirect_and_returns_final_response() {
        RedirectApi client = new CyranoRestClientBuilder()
                .baseUri(baseUri)
                .followRedirects(true)
                .build(RedirectApi.class);

        try (Response response = client.execute()) {
            assertEquals(200, response.getStatus());
            assertNull(response.getLocation());
            assertNull(response.getHeaderString("Location"));
            assertEquals("followed redirect", response.readEntity(String.class));
        }
        assertEquals(1, redirectCount.get());
        assertEquals(1, redirectedCount.get());
    }

    @Test
    void readTimeout_wraps_transport_timeout_in_processing_exception() {
        SlowApi client = new CyranoRestClientBuilder()
                .baseUri(baseUri)
                .readTimeout(100, TimeUnit.MILLISECONDS)
                .build(SlowApi.class);

        assertThrows(ProcessingException.class, client::execute);
    }

    @Test
    void proxyAddress_rejects_null_host() {
        assertThrows(IllegalArgumentException.class,
                () -> new CyranoRestClientBuilder().proxyAddress(null, 8080));
    }

    @Test
    void proxyAddress_rejects_invalid_port_low_values() {
        CyranoRestClientBuilder builder = new CyranoRestClientBuilder();
        assertThrows(IllegalArgumentException.class, () -> builder.proxyAddress("localhost", -1));
        assertThrows(IllegalArgumentException.class, () -> builder.proxyAddress("localhost", 0));
    }

    @Test
    void proxyAddress_rejects_invalid_port_high_values() {
        assertThrows(IllegalArgumentException.class,
                () -> new CyranoRestClientBuilder().proxyAddress("localhost", 65536));
    }
}

