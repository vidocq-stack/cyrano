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
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers runtime M4-2 iteration: {@link ClientRequestFilter} pipeline /
 * {@link ClientResponseFilter} (JAX-RS §6.3 + MicroProfile Rest Client §4.2).
 *
 * <p>Checks: ordering by priority, abort via {@code abortWith(Response)}, header/URI
 * modification by a filter, reading the standard property
 * {@code org.eclipse.microprofile.rest.client.invokedMethod}, and the reverse
 * ordering of response filters relative to request filters.</p>
 */
class CyranoFilterPipelineTest {

    @Path("/api")
    interface EchoService {
        @GET
        @Path("/ping")
        String ping();
    }

    private HttpServer server;
    private URI baseUri;
    private final AtomicReference<String> capturedHeader = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ping", ex -> {
            capturedHeader.set(ex.getRequestHeaders().getFirst("X-Filter"));
            capturedPath.set(ex.getRequestURI().getPath());
            byte[] b = "pong".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/api/rerouted", ex -> {
            byte[] b = "rerouted".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** A filter can add a header before sending. */
    @Test
    void requestFilter_addsHeader() {
        EchoService client = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .register((ClientRequestFilter) ctx -> ctx.getHeaders().putSingle("X-Filter", "applied"))
                .build(EchoService.class);
        assertEquals("pong", client.ping());
        assertEquals("applied", capturedHeader.get());
    }

    /** A filter can rewrite the target URI. */
    @Test
    void requestFilter_rewritesUri() {
        EchoService client = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .register((ClientRequestFilter) ctx ->
                        ctx.setUri(URI.create(baseUri + "/api/rerouted")))
                .build(EchoService.class);
        assertEquals("rerouted", client.ping());
    }

    /** abortWith court-circuite le transport et renvoie la Response telle quelle. */
    @Test
    void requestFilter_abortShortCircuitsTransport() {
        capturedPath.set(null);
        //In unit-test we don't have RuntimeDelegate JAX-RS (no Cassini test-dep) — we build
        //So a Response manually via the same internal factory as the engine (CyranoLightResponse).
        //The full `Response.ok(...).build()` scenario is covered by the official TCK.
        EchoService client = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .register((ClientRequestFilter) ctx ->
                        ctx.abortWith(StubResponse.ofString(200, "aborted!")))
                .build(EchoService.class);
        assertEquals("aborted!", client.ping());
        //the server has never been contacted
        assertEquals(null, capturedPath.get());
    }

    /** A filter reads the MP-RC `invokedMethod` property and resolves it correctly. */
    @Test
    void requestFilter_exposesInvokedMethod() {
        AtomicReference<String> seen = new AtomicReference<>();
        EchoService client = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .register((ClientRequestFilter) ctx -> {
                    Object m = ctx.getProperty("org.eclipse.microprofile.rest.client.invokedMethod");
                    if (m instanceof java.lang.reflect.Method jm) seen.set(jm.getName());
                })
                .build(EchoService.class);
        client.ping();
        assertEquals("ping", seen.get());
    }

    /** Request filters: ascending priority. Response filters: descending priority. */
    @Test
    void filterPriorities_ascendingRequest_descendingResponse() {
        List<String> reqOrder = new ArrayList<>();
        List<String> respOrder = new ArrayList<>();

        class ReqA implements ClientRequestFilter {
            public void filter(ClientRequestContext ctx) { reqOrder.add("A"); }
        }
        class ReqB implements ClientRequestFilter {
            public void filter(ClientRequestContext ctx) { reqOrder.add("B"); }
        }
        class RespA implements ClientResponseFilter {
            public void filter(ClientRequestContext req, ClientResponseContext resp) { respOrder.add("A"); }
        }
        class RespB implements ClientResponseFilter {
            public void filter(ClientRequestContext req, ClientResponseContext resp) { respOrder.add("B"); }
        }

        EchoService client = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .register(new ReqA(), 3000)
                .register(new ReqB(), 1000)
                .register(new RespA(), 3000)
                .register(new RespB(), 1000)
                .build(EchoService.class);
        client.ping();
        assertEquals(List.of("B", "A"), reqOrder, "request: ascending priority");
        assertEquals(List.of("A", "B"), respOrder, "response: descending priority");
    }

    /** A ClientResponseFilter can observe or modify the status. */
    @Test
    void responseFilter_canChangeStatus() {
        EchoService client = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .register((ClientResponseFilter) (req, resp) -> resp.setStatus(200))
                .build(EchoService.class);
        assertEquals("pong", client.ping());
    }

    /** A response filter correctly sees the response aborted by a request filter. */
    @Test
    void responseFilter_seesAbortedResponse() {
        AtomicReference<Integer> seenStatus = new AtomicReference<>();
        EchoService client = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .register((ClientRequestFilter) ctx ->
                        ctx.abortWith(StubResponse.ofString(418, "teapot")))
                .register((ClientResponseFilter) (req, resp) -> seenStatus.set(resp.getStatus()))
                .build(EchoService.class);
        assertNotNull(catchWebApp(() -> client.ping()));
        assertEquals(418, seenStatus.get());
    }

    private static Throwable catchWebApp(Runnable r) {
        try { r.run(); return null; } catch (Throwable t) { return t; }
    }
}



