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

import com.sun.net.httpserver.HttpServer;
import io.vidocq.cyrano.internal.CyranoSseTransportTest.CollectingSubscriber;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Element mapping, abort and failure paths of {@code Publisher<T>} return types
 * (MP Rest Client 4.0 §10 — Server Sent Events).
 */
@Timeout(30)
class CyranoSseMappingTest {

    private static final String SSE_HEADER = "Content-Type";
    private static final String EVENT_STREAM = "text/event-stream";

    /** JSON-B bound element type. */
    public static final class Reading {
        private int temp;

        public int getTemp() {
            return temp;
        }

        public void setTemp(int temp) {
            this.temp = temp;
        }
    }

    /** An element type only readable by a registered MessageBodyReader. */
    public record Tag(String value) {
    }

    public static final class TagReader implements MessageBodyReader<Tag> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Tag.class;
        }

        @Override
        public Tag readFrom(Class<Tag> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            return new Tag("#" + new String(entityStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Path("/sse")
    public interface MappingService {
        @GET
        @Path("/arrays")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<JsonArray> arrays();

        @GET
        @Path("/values")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<JsonValue> values();

        @GET
        @Path("/readings")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<Reading> readings();

        @GET
        @Path("/tags")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<Tag> tags();

        @GET
        @Path("/broken")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<JsonArray> broken();

        @GET
        @Path("/utf8")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<String> utf8();

        @GET
        @Path("/endless")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<String> endless();

        @GET
        @Path("/objects")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<Object> objects();
    }

    private HttpServer server;
    private URI baseUri;
    private final CountDownLatch endlessClosed = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        events("/sse/arrays", ": comment only\n\n", "data: [1,2]\n\n");
        events("/sse/values", "data: 7\n\n", "data: \"text\"\n\n");
        events("/sse/readings", "data: {\"temp\":21}\n\n");
        events("/sse/tags", "data: red\n\n");
        events("/sse/broken", "data: not json\n\n");
        events("/sse/objects", "event: tick\ndata: 1\n\n");
        server.createContext("/sse/utf8", ex -> {
            ex.getResponseHeaders().set(SSE_HEADER, EVENT_STREAM);
            ex.sendResponseHeaders(200, 0);
            byte[] payload = "data: café\n\n".getBytes(StandardCharsets.UTF_8);
            int split = payload.length - 3; // inside the two bytes of the e-acute
            try (OutputStream os = ex.getResponseBody()) {
                os.write(payload, 0, split);
                os.flush();
                sleep();
                os.write(payload, split, payload.length - split);
                os.flush();
            }
            ex.close();
        });
        server.createContext("/sse/endless", ex -> {
            ex.getResponseHeaders().set(SSE_HEADER, EVENT_STREAM);
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                for (int i = 0; i < 200; i++) {
                    os.write(("data: e" + i + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    sleep();
                }
            } catch (IOException _) {
                // the client cancelled: the connection is gone
            } finally {
                endlessClosed.countDown();
            }
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void events(String path, String... events) {
        server.createContext(path, ex -> {
            ex.getResponseHeaders().set(SSE_HEADER, EVENT_STREAM);
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                for (String event : events) {
                    os.write(event.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }
            ex.close();
        });
    }

    private static void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private MappingService client() {
        return new CyranoRestClientBuilder().baseUri(baseUri).register(TagReader.class).build(MappingService.class);
    }

    private static <T> CollectingSubscriber<T> collect(Publisher<T> publisher) throws InterruptedException {
        CollectingSubscriber<T> subscriber = new CollectingSubscriber<>(0);
        publisher.subscribe(subscriber);
        subscriber.await();
        return subscriber;
    }

    @Test
    void comment_only_events_are_dropped_for_a_typed_publisher() throws InterruptedException {
        CollectingSubscriber<JsonArray> subscriber = collect(client().arrays());

        assertNull(subscriber.error.get());
        assertEquals(1, subscriber.items.size());
        assertEquals(2, subscriber.items.getFirst().size());
    }

    @Test
    void json_values_are_parsed() throws InterruptedException {
        CollectingSubscriber<JsonValue> subscriber = collect(client().values());

        assertNull(subscriber.error.get());
        assertEquals(7, ((JsonNumber) subscriber.items.get(0)).intValue());
        assertEquals(JsonValue.ValueType.STRING, subscriber.items.get(1).getValueType());
    }

    @Test
    void pojo_events_are_bound_with_json_b() throws InterruptedException {
        CollectingSubscriber<Reading> subscriber = collect(client().readings());

        assertNull(subscriber.error.get());
        assertEquals(21, subscriber.items.getFirst().getTemp());
    }

    @Test
    void a_registered_message_body_reader_reads_the_event_data() throws InterruptedException {
        CollectingSubscriber<Tag> subscriber = collect(client().tags());

        assertNull(subscriber.error.get());
        assertEquals(List.of(new Tag("#red")), subscriber.items);
    }

    @Test
    void an_unreadable_event_fails_the_stream() throws InterruptedException {
        CollectingSubscriber<JsonArray> subscriber = collect(client().broken());

        assertTrue(subscriber.items.isEmpty());
        assertInstanceOf(RuntimeException.class, subscriber.error.get());
    }

    @Test
    void a_multi_byte_character_split_across_reads_is_decoded() throws InterruptedException {
        CollectingSubscriber<String> subscriber = collect(client().utf8());

        assertNull(subscriber.error.get());
        assertEquals(List.of("café"), subscriber.items);
    }

    @Test
    void an_object_publisher_receives_the_inbound_events() throws InterruptedException {
        CollectingSubscriber<Object> subscriber = collect(client().objects());

        assertNull(subscriber.error.get());
        jakarta.ws.rs.sse.InboundSseEvent event =
                assertInstanceOf(jakarta.ws.rs.sse.InboundSseEvent.class, subscriber.items.getFirst());
        assertEquals("tick", event.getName());
    }

    @Test
    void cancelling_the_only_subscriber_stops_reading_the_stream() throws InterruptedException {
        CountDownLatch first = new CountDownLatch(1);
        client().endless().subscribe(new Subscriber<>() {
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
                subscription.cancel();
                first.countDown();
            }

            @Override
            public void onError(Throwable t) {
                first.countDown();
            }

            @Override
            public void onComplete() {
                first.countDown();
            }
        });

        assertTrue(first.await(10, TimeUnit.SECONDS));
        assertTrue(endlessClosed.await(15, TimeUnit.SECONDS), "the server must see the connection closed");
    }

    @Test
    void a_connection_failure_fails_the_stream_with_processing_exception() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        MappingService unreachable = new CyranoRestClientBuilder()
                .baseUri(URI.create("http://127.0.0.1:" + freePort))
                .build(MappingService.class);

        CollectingSubscriber<JsonArray> subscriber = collect(unreachable.arrays());

        assertInstanceOf(ProcessingException.class, subscriber.error.get());
    }

    @Test
    void a_request_aborted_with_an_error_status_fails_the_stream() throws InterruptedException {
        MappingService aborted = new CyranoRestClientBuilder().baseUri(baseUri)
                .register((ClientRequestFilter) ctx -> ctx.abortWith(StubResponse.ofString(503, "down")))
                .build(MappingService.class);

        CollectingSubscriber<JsonArray> subscriber = collect(aborted.arrays());

        WebApplicationException e = assertInstanceOf(WebApplicationException.class, subscriber.error.get());
        assertEquals(503, e.getResponse().getStatus());
    }

    @Test
    void a_request_aborted_with_a_success_status_completes_empty() throws InterruptedException {
        MappingService aborted = new CyranoRestClientBuilder().baseUri(baseUri)
                .register((ClientRequestFilter) ctx -> ctx.abortWith(StubResponse.ofString(200, "ok")))
                .build(MappingService.class);

        CollectingSubscriber<JsonArray> subscriber = collect(aborted.arrays());

        assertNull(subscriber.error.get());
        assertTrue(subscriber.items.isEmpty());
    }
}
