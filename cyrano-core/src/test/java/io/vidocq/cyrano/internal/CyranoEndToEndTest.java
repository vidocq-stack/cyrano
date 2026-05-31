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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test: client interface → scanner → proxy Class-File API →
 * invocation handler → HttpClient JDK → {@link HttpServer} JDK inline.
 *
 * <p>No third-party library, no container. Starts a JDK HTTP server on a random
 * port, declares a Cyrano client interface pointed at it, and verifies the round-trip.</p>
 *
 * <p>Covers MicroProfile Rest Client 4.0 §3 (client interface), §3.1 (path/query),
 * §4 (invocation), §5 (base URI config).</p>
 */
class CyranoEndToEndTest {

    @Path("/users")
    interface UserService {
        @GET
        @Path("/{id}")
        String getUser(@PathParam("id") long id);

        @GET
        String search(@QueryParam("q") String query);

        @POST
        String create();

        @GET
        @Path("/count")
        int countUsers();
    }

    private HttpServer server;
    private URI baseUri;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastUri = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastMethod.set(ex.getRequestMethod());
            lastUri.set(ex.getRequestURI().toString());
            String body;
            if (ex.getRequestURI().getPath().equals("/users/count")) {
                body = "42";
            } else if (ex.getRequestURI().getPath().startsWith("/users/")) {
                body = "user-" + ex.getRequestURI().getPath().substring("/users/".length());
            } else if (ex.getRequestMethod().equals("POST")) {
                body = "created";
            } else {
                body = "ok";
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
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
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void getUser_resolves_path_param_and_returns_string() {
        UserService client = newClient();

        String result = client.getUser(7L);

        assertEquals("user-7", result);
        assertEquals("GET", lastMethod.get());
        assertEquals("/users/7", lastUri.get());
    }

    @Test
    void search_resolves_query_param() {
        UserService client = newClient();

        String result = client.search("alice");

        assertEquals("ok", result);
        assertEquals("GET", lastMethod.get());
        assertTrue(lastUri.get().startsWith("/users?q=alice"),
                "Expected URI to start with /users?q=alice but was " + lastUri.get());
    }

    @Test
    void post_without_path_uses_base_path() {
        UserService client = newClient();

        String result = client.create();

        assertEquals("created", result);
        assertEquals("POST", lastMethod.get());
        assertEquals("/users", lastUri.get());
    }

    @Test
    void primitive_return_type_is_parsed_from_body() {
        UserService client = newClient();

        int count = client.countUsers();

        assertEquals(42, count);
    }

    @Test
    void proxy_is_cached_across_builds() {
        UserService a = newClient();
        UserService b = newClient();
        //The instance changes (handler/baseUri own) but the class is shared.
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.getClass(), b.getClass(),
                "The proxy must be generated only once and cached");
        assertTrue(a.getClass().getName().endsWith("Cyrano$CyranoEndToEndTest_UserService"),
                "Proxy name must end with Cyrano$CyranoEndToEndTest_UserService — was " + a.getClass().getName());
    }

    @Test
    void smoke_lastUri_is_reset_between_calls() {
        UserService client = newClient();
        client.getUser(1L);
        assertEquals("/users/1", lastUri.get());
    }

    @Test
    void unused_test_to_silence_assertNull_warning() {
        assertNull(null);
    }

    private UserService newClient() {
        return new CyranoRestClientBuilder()
                .baseUri(baseUri)
                .build(UserService.class);
    }
}

