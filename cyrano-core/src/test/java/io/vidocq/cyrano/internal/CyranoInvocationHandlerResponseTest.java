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
package io.vidocq.cyrano.internal;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbTransient;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.ReaderInterceptor;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.ext.AsyncInvocationInterceptor;
import org.eclipse.microprofile.rest.client.ext.AsyncInvocationInterceptorFactory;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Response side of {@link CyranoInvocationHandler}: return type mapping (MP Rest Client 4.0 §3),
 * registered readers and JSON-B resolvers (§4.2), exception mappers and the default mapper (§5.4,
 * §8), asynchronous methods (§7) and aborted SSE publishers (§10).
 */
class CyranoInvocationHandlerResponseTest {

    private static final String BOOM = "boom";
    private static final String ABORTED = "aborted";
    private static final String DISABLE_DEFAULT_MAPPER = "microprofile.rest.client.disable.default.mapper";
    private static final String HIDDEN_JSON = "{\"name\":\"n\",\"years\":41,\"big\":9000000000,"
            + "\"active\":true,\"secret\":\"s\",\"code\":7,\"unknown\":0}";

    private final RecordingHttpServer server = new RecordingHttpServer();

    @BeforeAll
    static void installRuntimeDelegate() {
        TestRuntimeDelegate.install();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    public static final class Payload {
        public String value;
    }

    /** Fields without accessors, filled from the JSON body after JSON-B binding. */
    public static final class Hidden {
        private static final String SHARED = "shared";
        private final String fixed = "fixed";
        private String name;
        @JsonbProperty("years")
        private int age;
        private long big;
        private boolean active;
        @JsonbTransient
        private String secret;
        private String code;

        String describe() {
            return name + "/" + age + "/" + big + "/" + active + "/" + secret + "/" + code + "/" + fixed + "/" + SHARED;
        }
    }

    public record Wrapped(String text) {
    }

    @Path("/r")
    public interface ResponseApi {
        @GET
        String text();

        @GET
        Optional<String> optionalText();

        @GET
        Optional<Payload> optionalPayload();

        @GET
        List<Payload> list();

        @GET
        Set<String> set();

        @GET
        Map<String, Integer> map();

        @GET
        Payload payload();

        @GET
        Hidden hidden();

        @GET
        Wrapped wrapped();

        @GET
        Response response();

        @GET
        Object raw();

        @GET
        void nothing();

        @GET
        CompletionStage<String> async();

        @GET
        @Produces("text/event-stream")
        Publisher<String> events();
    }

    /** Reads the body as a {@link Wrapped} (spec §4.2 MessageBodyReader). */
    public static final class WrappedReader implements MessageBodyReader<Wrapped> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Wrapped.class;
        }

        @Override
        public Wrapped readFrom(Class<Wrapped> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                                MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            return new Wrapped(new String(entityStream.readAllBytes(), StandardCharsets.UTF_8) + "@" + mediaType);
        }
    }

    public static final class FailingReader implements MessageBodyReader<Wrapped> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Wrapped.class;
        }

        @Override
        public Wrapped readFrom(Class<Wrapped> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                                MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            throw new IOException(BOOM);
        }
    }

    /** Ignores every response: returns no throwable. */
    public static final class SilentMapper implements ResponseExceptionMapper<RuntimeException> {
        @Override
        public RuntimeException toThrowable(Response response) {
            return null;
        }

        @Override
        public int getPriority() {
            return 1;
        }
    }

    /** Maps 409 to an {@link IllegalStateException}. */
    public static final class ConflictMapper implements ResponseExceptionMapper<IllegalStateException> {
        @Override
        public IllegalStateException toThrowable(Response response) {
            return new IllegalStateException("conflict " + response.getStatus());
        }

        @Override
        public boolean handles(int status, MultivaluedMap<String, Object> headers) {
            return status == 409;
        }

        /** Explicit: the default reads {@code @Priority}, absent from this test module path. */
        @Override
        public int getPriority() {
            return 2;
        }
    }

    /** Records the life cycle of its interceptors (spec §7.2). */
    public static final class RecordingFactory implements AsyncInvocationInterceptorFactory {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public AsyncInvocationInterceptor newInterceptor() {
            return new AsyncInvocationInterceptor() {
                @Override
                public void prepareContext() {
                    events.add("prepare");
                }

                @Override
                public void applyContext() {
                    events.add("apply");
                }

                @Override
                public void removeContext() {
                    events.add("remove");
                }
            };
        }
    }

    /** Interceptors whose every step fails: the call must still complete. */
    public static final class ThrowingFactory implements AsyncInvocationInterceptorFactory {
        @Override
        public AsyncInvocationInterceptor newInterceptor() {
            return new AsyncInvocationInterceptor() {
                @Override
                public void prepareContext() {
                    throw new IllegalStateException(BOOM);
                }

                @Override
                public void applyContext() {
                    throw new IllegalStateException(BOOM);
                }

                @Override
                public void removeContext() {
                    throw new IllegalStateException(BOOM);
                }
            };
        }
    }

    public static final class BrokenFactory implements AsyncInvocationInterceptorFactory {
        @Override
        public AsyncInvocationInterceptor newInterceptor() {
            throw new IllegalStateException(BOOM);
        }
    }

    public static final class NullFactory implements AsyncInvocationInterceptorFactory {
        @Override
        public AsyncInvocationInterceptor newInterceptor() {
            return null;
        }
    }

    private RestClientBuilder builder() {
        return RestClientBuilder.newBuilder().baseUri(server.baseUri());
    }

    private ResponseApi client() {
        return builder().build(ResponseApi.class);
    }

    private static ClientRequestFilter abortWith(int status, String body) {
        return ctx -> ctx.abortWith(StubResponse.ofString(status, body));
    }

    @Test
    void optional_return_is_empty_on_404_and_present_otherwise_spec_section3() {
        server.respond(404, "missing");
        assertTrue(client().optionalText().isEmpty());

        server.respond(200, "{\"value\":\"v\"}");
        assertEquals("v", client().optionalPayload().orElseThrow().value);
    }

    @Test
    void collection_and_map_returns_are_read_with_json_b() {
        server.respond(200, "[{\"value\":\"a\"},{\"value\":\"b\"}]");
        List<Payload> list = client().list();
        assertEquals(2, list.size());
        assertEquals("b", list.get(1).value);

        server.respond(200, "[\"x\",\"y\",\"x\"]");
        assertEquals(Set.of("x", "y"), client().set());

        server.respond(200, "{\"one\":1}");
        assertEquals(Map.of("one", 1), client().map());
    }

    @Test
    void empty_body_maps_to_a_null_pojo() {
        server.respond(200, "");
        assertNull(client().payload());
    }

    @Test
    void private_fields_without_accessors_are_filled_from_the_json_body() {
        server.respond(200, HIDDEN_JSON);

        Hidden hidden = client().hidden();

        assertEquals("n/41/9000000000/true/null/7/fixed/shared", hidden.describe());
    }

    /** BUG-20261028-21: header names are case-insensitive, JDK servers send {@code Content-type}. */
    @Test
    void registered_reader_reads_the_body_with_the_response_media_type_spec_section4_2() {
        server.respond(200, "raw").contentType("text/plain");
        assertEquals("raw@text/plain", builder().register(new WrappedReader()).build(ResponseApi.class).wrapped().text());
    }

    @Test
    void reader_interceptors_run_around_the_registered_reader() {
        ReaderInterceptor suffix = ctx -> new Wrapped(((Wrapped) ctx.proceed()).text() + "!");
        ResponseApi client = builder().register(new WrappedReader()).register(suffix).build(ResponseApi.class);

        assertEquals("ok@*/*!", client.wrapped().text());
    }

    @Test
    void reader_io_failure_is_a_processing_exception() {
        ResponseApi client = builder().register(new FailingReader()).build(ResponseApi.class);

        ProcessingException e = assertThrows(ProcessingException.class, client::wrapped);
        assertInstanceOf(IOException.class, e.getCause());
    }

    @Test
    void context_resolver_supplies_the_jsonb_instance_and_failing_resolvers_are_skipped() throws Exception {
        AtomicInteger resolved = new AtomicInteger();
        try (Jsonb jsonb = JsonbBuilder.create()) {
            ContextResolver<Jsonb> failing = type -> {
                throw new IllegalStateException(BOOM);
            };
            ContextResolver<Object> unrelated = type -> null;
            ContextResolver<Jsonb> working = type -> {
                resolved.incrementAndGet();
                return jsonb;
            };
            server.respond(200, "{\"value\":\"v\"}");
            ResponseApi client = builder().register(failing).register(unrelated).register(working)
                    .build(ResponseApi.class);

            assertEquals("v", client.payload().value);
            assertTrue(resolved.get() > 0);
        }
    }

    @Test
    void debug_switches_do_not_change_the_result() {
        System.setProperty("cyrano.debug.providers", "true");
        System.setProperty("cyrano.debug.jsonb", "true");
        try {
            server.respond(200, "{\"value\":\"v\"}");
            assertEquals("v", client().payload().value);
        } finally {
            System.clearProperty("cyrano.debug.providers");
            System.clearProperty("cyrano.debug.jsonb");
        }
    }

    @Test
    void error_status_raises_the_default_web_application_exception_spec_section8() {
        server.respond(500, "failure");

        WebApplicationException e = assertThrows(WebApplicationException.class, client()::text);

        assertEquals(500, e.getResponse().getStatus());
    }

    @Test
    void disabled_default_mapper_returns_the_raw_response_spec_section8() {
        server.respond(503, "down");
        ResponseApi client = builder().property(DISABLE_DEFAULT_MAPPER, true).build(ResponseApi.class);

        assertEquals(503, client.response().getStatus());
        Response raw = assertInstanceOf(Response.class, client.raw());
        assertEquals("down", raw.readEntity(String.class));
        client.nothing();
        assertEquals("/r", server.last().rawPath());
    }

    @Test
    void user_mappers_run_by_priority_and_a_null_throwable_is_ignored_spec_section5_4() {
        ResponseApi client = builder().register(new SilentMapper()).register(new ConflictMapper())
                .build(ResponseApi.class);

        server.respond(409, "taken");
        IllegalStateException e = assertThrows(IllegalStateException.class, client::text);
        assertEquals("conflict 409", e.getMessage());

        server.respond(200, "fine");
        assertEquals("fine", client.text());
    }

    @Test
    void async_method_runs_the_interceptor_life_cycle_on_the_configured_executor_spec_section7() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        AtomicReference<String> filterThread = new AtomicReference<>();
        ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("custom-exec").factory());
        try {
            ResponseApi client = builder()
                    .executorService(executor)
                    .register(factory)
                    .register(new ThrowingFactory())
                    .register(new BrokenFactory())
                    .register(new NullFactory())
                    .register((ClientResponseFilter) (req, resp) -> filterThread.set(Thread.currentThread().getName()))
                    .build(ResponseApi.class);

            assertEquals("ok", client.async().toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(List.of("prepare", "apply", "remove"), factory.events);
            assertEquals("custom-exec", filterThread.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aborted_async_request_is_answered_asynchronously_with_its_filters() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        AtomicReference<String> filtered = new AtomicReference<>();
        ResponseApi client = builder()
                .register(factory)
                .register(new ThrowingFactory())
                .register(abortWith(200, ABORTED))
                .register((ClientResponseFilter) (req, resp) -> filtered.set(String.valueOf(resp.getStatus())))
                .build(ResponseApi.class);

        assertEquals(ABORTED, client.async().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(List.of("prepare", "apply", "remove"), factory.events);
        assertEquals("200", filtered.get());
        assertNull(server.last(), "an aborted request is never sent");
    }

    @Test
    void async_response_filter_io_failure_completes_exceptionally() {
        ClientResponseFilter failing = (req, resp) -> {
            throw new IOException(BOOM);
        };
        CompletableFuture<String> sent = builder().register(failing).build(ResponseApi.class)
                .async().toCompletableFuture();
        CompletableFuture<String> aborted = builder().register(failing).register(abortWith(200, ABORTED))
                .build(ResponseApi.class).async().toCompletableFuture();

        CompletionException e1 = assertThrows(CompletionException.class, sent::join);
        assertInstanceOf(ProcessingException.class, e1.getCause());
        CompletionException e2 = assertThrows(CompletionException.class, aborted::join);
        assertInstanceOf(ProcessingException.class, e2.getCause());
    }

    @Test
    void aborted_sse_request_yields_a_failed_or_completed_publisher_spec_section10() throws Exception {
        assertInstanceOf(WebApplicationException.class, subscribe(
                builder().register(abortWith(503, ABORTED)).build(ResponseApi.class).events()).get(10, TimeUnit.SECONDS));
        assertEquals("complete", subscribe(
                builder().register(abortWith(204, null)).build(ResponseApi.class).events()).get(10, TimeUnit.SECONDS));
    }

    /** Completes with the error of the publisher, or {@code "complete"}. */
    private static CompletableFuture<Object> subscribe(Publisher<String> publisher) {
        CompletableFuture<Object> outcome = new CompletableFuture<>();
        publisher.subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
                outcome.complete(item);
            }

            @Override
            public void onError(Throwable t) {
                outcome.complete(t);
            }

            @Override
            public void onComplete() {
                outcome.complete("complete");
            }
        });
        return outcome;
    }
}
