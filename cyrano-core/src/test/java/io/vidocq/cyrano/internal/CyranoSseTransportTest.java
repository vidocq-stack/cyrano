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
import jakarta.json.JsonObject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.InboundSseEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the SSE client support — MP Rest Client 4.0 §10
 * (reactive {@code org.reactivestreams.Publisher} return types over
 * {@code text/event-stream}). JDK {@link HttpServer} streams events
 * progressively (chunked, flushed per event), no third-party library.
 */
@Timeout(30)
class CyranoSseTransportTest {

    @Path("/sse")
    public interface SseService {
        @GET
        @Path("/strings")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<String> stringEvents();

        @GET
        @Path("/raw")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<InboundSseEvent> rawEvents();

        @GET
        @Path("/json")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<JsonObject> jsonEvents();

        @GET
        @Path("/many")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<String> manyEvents();

        @GET
        @Path("/partial")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<String> partialEvent();

        @GET
        @Path("/missing")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<String> missing();
    }

    /**
     * Hand-written Reactive Streams subscriber (no Mockito) with an explicit
     * request strategy — {@code requestPerOnNext <= 0} means request
     * {@code Long.MAX_VALUE} upfront.
     */
    static final class CollectingSubscriber<T> implements Subscriber<T> {
        final List<T> items = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final ConcurrentLinkedQueue<String> signals = new ConcurrentLinkedQueue<>();
        private final long requestPerOnNext;
        private volatile Subscription subscription;

        CollectingSubscriber(long requestPerOnNext) {
            this.requestPerOnNext = requestPerOnNext;
        }

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s;
            signals.add("onSubscribe");
            s.request(requestPerOnNext <= 0 ? Long.MAX_VALUE : requestPerOnNext);
        }

        @Override
        public void onNext(T item) {
            items.add(item);
            signals.add("onNext");
            if (requestPerOnNext > 0) {
                subscription.request(requestPerOnNext);
            }
        }

        @Override
        public void onError(Throwable t) {
            error.set(t);
            signals.add("onError");
            done.countDown();
        }

        @Override
        public void onComplete() {
            signals.add("onComplete");
            done.countDown();
        }

        void await() throws InterruptedException {
            assertTrue(done.await(15, TimeUnit.SECONDS),
                    "Subscriber did not terminate — signals so far: " + signals);
        }
    }

    private HttpServer server;
    private URI baseUri;
    private final AtomicReference<String> lastAcceptHeader = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sse/strings", ex -> {
            lastAcceptHeader.set(ex.getRequestHeaders().getFirst("Accept"));
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                writeAndFlush(os, "data: one\n\n");
                writeAndFlush(os, "data: two\n\n");
                writeAndFlush(os, "data: three\n\n");
            }
            ex.close(); //clean end of stream → subscriber onComplete
        });
        server.createContext("/sse/raw", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                writeAndFlush(os, ": keep-alive\n\n");
                writeAndFlush(os, "event: weather\nid: 1\nretry: 2500\ndata: sunny\ndata: later cloudy\n\n");
            }
            ex.close();
        });
        server.createContext("/sse/json", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                writeAndFlush(os, "data: {\"temp\":21,\"sky\":\"clear\"}\n\n");
                writeAndFlush(os, "data: {\"temp\":12,\"sky\":\"rain\"}\n\n");
            }
            ex.close();
        });
        server.createContext("/sse/many", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                for (int i = 0; i < 5; i++) {
                    writeAndFlush(os, "data: item-" + i + "\n\n");
                }
            }
            ex.close();
        });
        server.createContext("/sse/partial", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                writeAndFlush(os, "data: complete\n\n");
                //No trailing blank line: incomplete event, must be discarded at EOF.
                writeAndFlush(os, "data: never-dispatched\n");
            }
            ex.close();
        });
        server.createContext("/sse/missing", ex -> {
            byte[] payload = "not found".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, payload.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void writeAndFlush(OutputStream os, String event) throws java.io.IOException {
        os.write(event.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private SseService newClient() {
        return new CyranoRestClientBuilder()
                .baseUri(baseUri)
                .build(SseService.class);
    }

    @Test
    void dataOnly_events_as_publisher_of_string_and_server_close_completes() throws Exception {
        var subscriber = new CollectingSubscriber<String>(0);
        newClient().stringEvents().subscribe(subscriber);
        subscriber.await();

        assertNull(subscriber.error.get(), "Unexpected error: " + subscriber.error.get());
        assertEquals(List.of("one", "two", "three"), subscriber.items);
        assertEquals("text/event-stream", lastAcceptHeader.get(),
                "@Produces(SERVER_SENT_EVENTS) must flow into the Accept header");
    }

    @Test
    void named_and_commentOnly_events_as_publisher_of_inboundSseEvent() throws Exception {
        var subscriber = new CollectingSubscriber<InboundSseEvent>(0);
        newClient().rawEvents().subscribe(subscriber);
        subscriber.await();

        assertNull(subscriber.error.get(), "Unexpected error: " + subscriber.error.get());
        assertEquals(2, subscriber.items.size(), "events: " + subscriber.items);

        InboundSseEvent comment = subscriber.items.get(0);
        assertEquals("keep-alive", comment.getComment());
        assertTrue(comment.isEmpty(), "comment-only event carries no data");
        assertNull(comment.getName());

        InboundSseEvent named = subscriber.items.get(1);
        assertEquals("weather", named.getName());
        assertEquals("1", named.getId());
        assertEquals("sunny\nlater cloudy", named.readData());
        assertTrue(named.isReconnectDelaySet());
        assertEquals(2500L, named.getReconnectDelay());
    }

    @Test
    void jsonObject_events_are_deserialized_via_readData_semantics() throws Exception {
        var subscriber = new CollectingSubscriber<JsonObject>(0);
        newClient().jsonEvents().subscribe(subscriber);
        subscriber.await();

        assertNull(subscriber.error.get(), "Unexpected error: " + subscriber.error.get());
        assertEquals(2, subscriber.items.size());
        assertEquals(21, subscriber.items.get(0).getInt("temp"));
        assertEquals("rain", subscriber.items.get(1).getString("sky"));
    }

    @Test
    void backpressure_request_one_at_a_time_receives_all_events_in_order() throws Exception {
        var subscriber = new CollectingSubscriber<String>(1);
        newClient().manyEvents().subscribe(subscriber);
        subscriber.await();

        assertNull(subscriber.error.get(), "Unexpected error: " + subscriber.error.get());
        assertEquals(List.of("item-0", "item-1", "item-2", "item-3", "item-4"), subscriber.items);
    }

    @Test
    void incomplete_event_at_end_of_stream_is_discarded() throws Exception {
        var subscriber = new CollectingSubscriber<String>(0);
        newClient().partialEvent().subscribe(subscriber);
        subscriber.await();

        assertNull(subscriber.error.get(), "Unexpected error: " + subscriber.error.get());
        assertEquals(List.of("complete"), subscriber.items);
    }

    @Test
    void error_status_fails_the_stream_with_webApplicationException() throws Exception {
        var subscriber = new CollectingSubscriber<String>(0);
        newClient().missing().subscribe(subscriber);
        subscriber.await();

        assertTrue(subscriber.items.isEmpty());
        Throwable error = subscriber.error.get();
        assertInstanceOf(WebApplicationException.class, error);
        assertEquals(404, ((WebApplicationException) error).getResponse().getStatus());
    }
}
