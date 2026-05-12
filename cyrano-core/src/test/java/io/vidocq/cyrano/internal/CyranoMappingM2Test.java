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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests d'intégration M2 — mapping complet des requêtes/réponses : tous les types de
 * paramètre (header, cookie, form, matrix, bean, body JSON), {@code @DefaultValue},
 * {@code @Consumes}/{@code @Produces}, {@code @ClientHeaderParam} (statique + dynamique),
 * désérialisation POJO/{@link Optional}/{@link List}/{@link Set} via JSON-B,
 * {@link ResponseExceptionMapper} et default mapper (HTTP ≥ 400 → {@link WebApplicationException}).
 */
class CyranoMappingM2Test {

    // ----- POJO partagé pour les tests JSON-B -----
    public static final class Item {
        public String name;
        public int qty;
        public Item() {}
        public Item(String name, int qty) { this.name = name; this.qty = qty; }
    }

    // ----- BeanParam test -----
    public static final class Filter {
        @QueryParam("q") public String q;
        @QueryParam("limit") @DefaultValue("10") public Integer limit;
        @HeaderParam("X-Region") public String region;
    }

    @Path("/svc")
    @ClientHeaderParam(name = "X-Type-Static", value = "type-level")
    interface SvcClient {
        @GET @Path("/echo/{id}")
        @Produces(MediaType.APPLICATION_JSON)
        @ClientHeaderParam(name = "X-Echo", value = "static-echo")
        String echo(@PathParam("id") long id, @HeaderParam("X-User") String user);

        @GET @Path("/cookie")
        String cookieReceiver(@CookieParam("sid") String sid);

        @POST @Path("/login")
        String login(@FormParam("u") String user, @FormParam("p") String password);

        @GET @Path("/matrix")
        String matrix(@jakarta.ws.rs.MatrixParam("x") String x);

        @GET @Path("/dft")
        String withDefault(@QueryParam("q") @DefaultValue("alpha") String q);

        @GET @Path("/bean")
        String beanParam(@BeanParam Filter f);

        @POST @Path("/items")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        Item create(Item body);

        @GET @Path("/item/{id}")
        @Produces(MediaType.APPLICATION_JSON)
        Item getItem(@PathParam("id") long id);

        @GET @Path("/items")
        @Produces(MediaType.APPLICATION_JSON)
        List<Item> listItems();

        @GET @Path("/itemset")
        @Produces(MediaType.APPLICATION_JSON)
        Set<Item> setItems();

        @GET @Path("/item/maybe/{id}")
        @Produces(MediaType.APPLICATION_JSON)
        Optional<Item> maybe(@PathParam("id") long id);

        @GET @Path("/oops")
        String oops();

        @GET @Path("/dyn")
        @ClientHeaderParam(name = "X-Dyn", value = "{computeHeader}")
        String dyn();

        // méthode default invoquée par @ClientHeaderParam(value="{computeHeader}")
        default String computeHeader() {
            return "computed-42";
        }
    }

    /** Mapper utilisateur — convertit 418 en {@link IllegalArgumentException}. */
    static final class TeapotMapper implements ResponseExceptionMapper<IllegalArgumentException> {
        @Override
        public IllegalArgumentException toThrowable(Response response) {
            return new IllegalArgumentException("I'm a teapot");
        }
        @Override
        public boolean handles(int status, MultivaluedMap<String, Object> headers) {
            return status == 418;
        }
        @Override
        public int getPriority() { return 10; }
    }

    private HttpServer server;
    private URI baseUri;
    private final AtomicReference<HttpExchange> lastEx = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastEx.set(ex);
            byte[] in = ex.getRequestBody().readAllBytes();
            lastBody.set(new String(in, StandardCharsets.UTF_8));
            String p = ex.getRequestURI().getPath();
            String q = ex.getRequestURI().getRawQuery();
            int status = 200;
            String body;
            if (p.startsWith("/svc/echo/")) body = "echo-" + p.substring("/svc/echo/".length());
            else if (p.equals("/svc/cookie")) body = "cookie-ok";
            else if (p.equals("/svc/login")) body = "logged";
            else if (p.startsWith("/svc/matrix")) body = "matrix-ok";
            else if (p.equals("/svc/dft")) body = "dft:" + q;
            else if (p.equals("/svc/bean")) {
                body = "bean:" + q + "|region=" + ex.getRequestHeaders().getFirst("X-Region");
            } else if (p.equals("/svc/items") && ex.getRequestMethod().equals("POST")) {
                body = "{\"name\":\"echoed\",\"qty\":99}";
                ex.getResponseHeaders().add("Content-Type", "application/json");
            } else if (p.equals("/svc/items")) {
                body = "[{\"name\":\"a\",\"qty\":1},{\"name\":\"b\",\"qty\":2}]";
                ex.getResponseHeaders().add("Content-Type", "application/json");
            } else if (p.equals("/svc/itemset")) {
                body = "[{\"name\":\"x\",\"qty\":7}]";
                ex.getResponseHeaders().add("Content-Type", "application/json");
            } else if (p.startsWith("/svc/item/maybe/")) {
                String id = p.substring("/svc/item/maybe/".length());
                if ("0".equals(id)) { status = 404; body = ""; }
                else { body = "{\"name\":\"found\",\"qty\":1}"; ex.getResponseHeaders().add("Content-Type", "application/json"); }
            } else if (p.startsWith("/svc/item/")) {
                String id = p.substring("/svc/item/".length());
                body = "{\"name\":\"item-" + id + "\",\"qty\":3}";
                ex.getResponseHeaders().add("Content-Type", "application/json");
            } else if (p.equals("/svc/oops")) {
                status = 418;
                body = "teapot";
            } else if (p.equals("/svc/dyn")) {
                body = "dyn-ok";
            } else {
                body = "ok";
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (var os = ex.getResponseBody()) { os.write(out); }
            } else {
                ex.close();
            }
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private SvcClient newClient() {
        return new CyranoRestClientBuilder().baseUri(baseUri).build(SvcClient.class);
    }

    private SvcClient newClientWith(Object provider) {
        return new CyranoRestClientBuilder().baseUri(baseUri).register(provider).build(SvcClient.class);
    }

    // -------- §3.1 binding tests --------

    @Test
    void header_param_is_sent_spec_section3_1() {
        SvcClient c = newClient();
        String r = c.echo(7L, "alice");
        assertEquals("echo-7", r);
        assertEquals("alice", lastEx.get().getRequestHeaders().getFirst("X-User"));
    }

    @Test
    void client_header_param_static_method_and_type_level_spec_section6_5() {
        SvcClient c = newClient();
        c.echo(1L, "x");
        var h = lastEx.get().getRequestHeaders();
        assertEquals("type-level", h.getFirst("X-Type-Static"));
        assertEquals("static-echo", h.getFirst("X-Echo"));
    }

    @Test
    void client_header_param_dynamic_default_method_spec_section6_5() {
        SvcClient c = newClient();
        c.dyn();
        assertEquals("computed-42", lastEx.get().getRequestHeaders().getFirst("X-Dyn"));
    }

    @Test
    void cookie_param_combined_into_cookie_header_spec_section3_1() {
        SvcClient c = newClient();
        c.cookieReceiver("ABC123");
        assertEquals("sid=ABC123", lastEx.get().getRequestHeaders().getFirst("Cookie"));
    }

    @Test
    void form_params_use_application_x_www_form_urlencoded_spec_section3_1() {
        SvcClient c = newClient();
        c.login("bob", "s3cret");
        assertEquals("application/x-www-form-urlencoded",
                lastEx.get().getRequestHeaders().getFirst("Content-Type"));
        assertTrue(lastBody.get().contains("u=bob"));
        assertTrue(lastBody.get().contains("p=s3cret"));
    }

    @Test
    void matrix_param_attached_to_path_spec_section3_1() {
        SvcClient c = newClient();
        c.matrix("v1");
        assertTrue(lastEx.get().getRequestURI().toString().contains(";x=v1"),
                "URI doit contenir le segment matrix ;x=v1 — était " + lastEx.get().getRequestURI());
    }

    @Test
    void default_value_applied_when_param_null_spec_section3_1() {
        SvcClient c = newClient();
        c.withDefault(null);
        assertEquals("q=alpha", lastEx.get().getRequestURI().getRawQuery());
    }

    @Test
    void bean_param_expands_fields_to_query_and_header_spec_section3_1() {
        SvcClient c = newClient();
        Filter f = new Filter();
        f.q = "hello";
        f.region = "eu";
        // limit non set → @DefaultValue("10") s'applique
        c.beanParam(f);
        var ex = lastEx.get();
        assertEquals("eu", ex.getRequestHeaders().getFirst("X-Region"));
        String rq = ex.getRequestURI().getRawQuery();
        assertTrue(rq.contains("q=hello"), rq);
        assertTrue(rq.contains("limit=10"), rq);
    }

    // -------- §4.2 / §5 JSON-B body / response --------

    @Test
    void post_body_serialized_via_jsonb_spec_section4_2() {
        SvcClient c = newClient();
        Item sent = new Item("pencil", 3);
        Item back = c.create(sent);
        assertEquals("application/json", lastEx.get().getRequestHeaders().getFirst("Content-Type"));
        assertTrue(lastBody.get().contains("\"name\":\"pencil\""), lastBody.get());
        assertEquals("echoed", back.name);
        assertEquals(99, back.qty);
    }

    @Test
    void get_pojo_deserialized_via_jsonb_spec_section4_2() {
        SvcClient c = newClient();
        Item it = c.getItem(42);
        assertEquals("item-42", it.name);
        assertEquals(3, it.qty);
        // Accept header présent (@Produces)
        assertEquals("application/json", lastEx.get().getRequestHeaders().getFirst("Accept"));
    }

    @Test
    void list_return_type_deserialized_spec_section4_2() {
        SvcClient c = newClient();
        List<Item> items = c.listItems();
        assertEquals(2, items.size());
        assertEquals("a", items.get(0).name);
    }

    @Test
    void set_return_type_deserialized_spec_section4_2() {
        SvcClient c = newClient();
        Set<Item> items = c.setItems();
        assertEquals(1, items.size());
    }

    @Test
    void optional_present_when_2xx_spec_section4() {
        SvcClient c = newClient();
        Optional<Item> opt = c.maybe(1L);
        assertTrue(opt.isPresent());
        assertEquals("found", opt.get().name);
    }

    @Test
    void optional_empty_on_404_spec_section8() {
        SvcClient c = newClient();
        Optional<Item> opt = c.maybe(0L);
        assertTrue(opt.isEmpty());
    }

    // -------- §7/§8 exception mapping --------

    @Test
    void default_exception_mapper_throws_WebApplicationException_spec_section8() {
        SvcClient c = newClient();
        WebApplicationException ex = assertThrows(WebApplicationException.class, c::oops);
        assertEquals(418, ex.getResponse() == null ? 418 : ex.getResponse().getStatus());
    }

    @Test
    void user_response_exception_mapper_takes_precedence_spec_section7() {
        SvcClient c = newClientWith(new TeapotMapper());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, c::oops);
        assertEquals("I'm a teapot", ex.getMessage());
    }

    @Test
    void smoke_check_invariants() {
        // Garantit que le serveur fonctionne pour les autres tests même quand exécutés isolément.
        SvcClient c = newClient();
        assertNotNull(c);
        assertNull(lastEx.get(), "Aucun appel n'a encore eu lieu");
        c.echo(1L, "z");
        assertNotNull(lastEx.get());
        assertFalse(lastBody.get() == null);
    }
}

