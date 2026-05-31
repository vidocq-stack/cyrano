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
import jakarta.json.JsonArray;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CyranoM45Test {

    @Path("/items")
    interface ItemApi {
        @GET
        @Path("/{id}")
        String byId(@PathParam("id") String id);
    }

    @Path("/parts")
    interface PartsApi {
        @GET
        Response list();
    }

    @Path("/feature")
    @RegisterProvider(TestFeature.class)
    interface AnnotatedFeatureApi {
        @GET
        String ping();
    }

    static final class TestFeature implements Feature {
        static volatile boolean invoked;

        static void reset() {
            invoked = false;
        }

        @Override
        public boolean configure(FeatureContext context) {
            invoked = true;
            return true;
        }
    }

    private HttpServer server;
    private URI baseUri;
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        TestFeature.reset();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/items", ex -> {
            lastPath.set(ex.getRequestURI().getPath());
            byte[] payload = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, payload.length);
            ex.getResponseBody().write(payload);
            ex.close();
        });
        server.createContext("/parts", ex -> {
            byte[] payload = "[{\"name\":\"a.txt\"}]".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, payload.length);
            ex.getResponseBody().write(payload);
            ex.close();
        });
        server.createContext("/feature", ex -> {
            byte[] payload = "pong".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, payload.length);
            ex.getResponseBody().write(payload);
            ex.close();
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void pathParam_keeps_colon_unescaped() {
        ItemApi client = new CyranoRestClientBuilder().baseUri(baseUri).build(ItemApi.class);

        assertEquals("ok", client.byId("Keyboard:123"));
        assertEquals("/items/Keyboard:123", lastPath.get());
    }

    @Test
    void feature_registered_on_interface_is_invoked_at_build_time() {
        AnnotatedFeatureApi client = new CyranoRestClientBuilder().baseUri(baseUri).build(AnnotatedFeatureApi.class);

        assertTrue(TestFeature.invoked, "Feature.configure(...) must be invoked during build()");
        assertEquals("pong", client.ping());
    }

    @Test
    void response_readEntity_supports_json_array_without_explicit_reader() {
        PartsApi client = new CyranoRestClientBuilder().baseUri(baseUri).build(PartsApi.class);

        try (Response response = client.list()) {
            JsonArray array = response.readEntity(JsonArray.class);
            assertEquals(1, array.size());
            assertEquals("a.txt", array.getJsonObject(0).getString("name"));
        }
    }
}

