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
package io.vidocq.cyrano.internal.sse;

import io.vidocq.cyrano.internal.CyranoLightResponse;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.sse.InboundSseEvent;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link Flow.Publisher} of mapped SSE events backed by a single
 * {@link SubmissionPublisher} — MP Rest Client 4.0 §10 (reactive Publisher
 * return types). {@code SubmissionPublisher} provides the Reactive Streams
 * conformance (backpressure, demand bookkeeping, terminal signal rules); this
 * class only feeds it from the HTTP response body.
 *
 * <p>The HTTP request is triggered lazily on the FIRST subscription — one
 * publisher instance equals one connection; later subscribers simply join the
 * shared {@code SubmissionPublisher}. The response body is consumed with an
 * explicit demand of one buffer list at a time; {@code submit(...)} blocks
 * when a subscriber saturates its buffer, which throttles the upstream reads
 * — blocking is fine, all callbacks run on virtual threads.</p>
 *
 * <p>Mapping from {@link InboundSseEvent} to the element type happens BEFORE
 * submission (single {@code SubmissionPublisher} stage, no intermediate
 * processor): a mapper returning {@code null} (e.g. comment-only event mapped
 * to its data) drops the event.</p>
 *
 * <p>No {@code synchronized}, no {@code ThreadLocal} — the only shared state
 * is an {@link AtomicBoolean} start latch; decoding state is confined to the
 * single-threaded body subscriber (HttpClient serializes its callbacks).</p>
 *
 * @param <T> published element type
 */
public final class SseEventPublisher<T> implements Flow.Publisher<T> {

    private final SubmissionPublisher<T> delegate;
    private final Supplier<CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>>> connection;
    private final Function<SseEventParser.SseEventData, InboundSseEvent> eventFactory;
    private final Function<InboundSseEvent, T> mapper;
    private final AtomicBoolean started = new AtomicBoolean();

    public SseEventPublisher(
            Supplier<CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>>> connection,
            Function<SseEventParser.SseEventData, InboundSseEvent> eventFactory,
            Function<InboundSseEvent, T> mapper) {
        //Virtual-thread executor: SubmissionPublisher delivery tasks are cheap,
        //possibly blocking (subscriber code) — one virtual thread per task.
        this.delegate = new SubmissionPublisher<>(
                Executors.newVirtualThreadPerTaskExecutor(), Flow.defaultBufferSize());
        this.connection = connection;
        this.eventFactory = eventFactory;
        this.mapper = mapper;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        delegate.subscribe(subscriber);
        if (started.compareAndSet(false, true)) {
            connect();
        }
    }

    private void connect() {
        connection.get().whenComplete((response, failure) -> {
            try {
                if (failure != null) {
                    delegate.closeExceptionally(asProcessingException(failure));
                    return;
                }
                int status = response.statusCode();
                if (status >= 400) {
                    //Release the connection, then fail the stream (spec §8 default
                    //mapping). CyranoLightResponse: no RuntimeDelegate needed.
                    response.body().subscribe(new CancellingSubscriber());
                    delegate.closeExceptionally(new WebApplicationException(
                            "HTTP " + status, CyranoLightResponse.ofStatus(status)));
                    return;
                }
                response.body().subscribe(new EventStreamSubscriber());
            } catch (RuntimeException e) {
                //whenComplete swallows callback exceptions — fail the stream instead.
                delegate.closeExceptionally(e);
            }
        });
    }

    private static Throwable asProcessingException(Throwable failure) {
        Throwable cause = failure instanceof CompletionException ce && ce.getCause() != null
                ? ce.getCause() : failure;
        return cause instanceof ProcessingException ? cause : new ProcessingException(cause);
    }

    /** Subscribes only to cancel — drains nothing, releases the HTTP exchange. */
    private static final class CancellingSubscriber implements Flow.Subscriber<List<ByteBuffer>> {
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.cancel(); }
        @Override public void onNext(List<ByteBuffer> item) { }
        @Override public void onError(Throwable throwable) { }
        @Override public void onComplete() { }
    }

    /**
     * Consumes the response body one buffer list at a time, decodes UTF-8
     * across buffer boundaries, feeds the SSE parser and submits mapped
     * events. Single-threaded: the JDK HttpClient serializes subscriber
     * callbacks, so no state here needs synchronization.
     */
    private final class EventStreamSubscriber implements Flow.Subscriber<List<ByteBuffer>> {

        private final SseEventParser parser = new SseEventParser();
        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        /** Undecoded trailing bytes of the previous buffer (split UTF-8 sequence). */
        private ByteBuffer pendingBytes;
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    CharBuffer chars = decode(buffer);
                    for (SseEventParser.SseEventData raw : parser.feed(chars)) {
                        T mapped = mapper.apply(eventFactory.apply(raw));
                        if (mapped != null) {
                            //Blocks on subscriber-buffer saturation → natural
                            //backpressure toward the HTTP connection.
                            delegate.submit(mapped);
                        }
                    }
                }
                if (delegate.getNumberOfSubscribers() == 0) {
                    //Every subscriber cancelled: stop reading, release resources.
                    subscription.cancel();
                    delegate.close();
                    return;
                }
                subscription.request(1);
            } catch (RuntimeException e) {
                subscription.cancel();
                delegate.closeExceptionally(e);
            }
        }

        private CharBuffer decode(ByteBuffer buffer) {
            ByteBuffer input;
            if (pendingBytes != null) {
                input = ByteBuffer.allocate(pendingBytes.remaining() + buffer.remaining());
                input.put(pendingBytes).put(buffer).flip();
            } else {
                input = buffer;
            }
            CharBuffer out = CharBuffer.allocate(input.remaining() + 1);
            decoder.decode(input, out, false);
            if (input.hasRemaining()) {
                //Incomplete multi-byte sequence at the end — carry it over.
                pendingBytes = ByteBuffer.allocate(input.remaining()).put(input).flip();
            } else {
                pendingBytes = null;
            }
            return out.flip();
        }

        @Override
        public void onError(Throwable throwable) {
            //SSE semantics: a dropped connection ENDS the stream (the protocol
            //delegates reconnection to the client, which Cyrano does not do
            //implicitly) — I/O failures map to onComplete, like Jersey's
            //EventInput. Anything else is a genuine error.
            Throwable cause = throwable instanceof CompletionException ce && ce.getCause() != null
                    ? ce.getCause() : throwable;
            if (cause instanceof IOException) {
                delegate.close();
            } else {
                delegate.closeExceptionally(cause);
            }
        }

        @Override
        public void onComplete() {
            //Per WHATWG, an event left incomplete at EOF is NOT dispatched —
            //the parser only emits on blank lines, nothing to flush here.
            delegate.close();
        }
    }
}
