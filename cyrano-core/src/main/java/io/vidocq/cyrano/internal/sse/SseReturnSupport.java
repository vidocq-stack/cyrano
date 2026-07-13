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

import io.vidocq.cyrano.internal.CyranoClientConfiguration;
import io.vidocq.cyrano.internal.CyranoHttpTransport;
import io.vidocq.cyrano.internal.CyranoLightResponse;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.InboundSseEvent;
import org.reactivestreams.FlowAdapters;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.http.HttpRequest;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Function;

/**
 * Bridge between the invocation handler and the SSE machinery for
 * {@code org.reactivestreams.Publisher<T>} return types — MP Rest Client 4.0
 * §10 (Server Sent Events, reactive Publisher return types).
 *
 * <p>This is the ONLY class in cyrano-core that imports
 * {@code org.reactivestreams} — {@code CyranoInvocationHandler} detects the
 * return type by NAME and only then touches this class, so the
 * reactive-streams jar stays optional at runtime ({@code requires static} in
 * module-info): clients that never declare a Publisher return type never load
 * it.</p>
 */
public final class SseReturnSupport {

    private SseReturnSupport() {
    }

    /**
     * Builds an {@code org.reactivestreams.Publisher} for a client method
     * returning {@code Publisher<T>} — the HTTP request is only sent when the
     * publisher receives its first subscriber.
     *
     * @param transport     the shared HTTP transport
     * @param request       the fully built request (Accept: text/event-stream)
     * @param elementType   the {@code T} of {@code Publisher<T>}
     * @param configuration client configuration (registered MessageBodyReaders)
     */
    public static Object createPublisher(CyranoHttpTransport transport, HttpRequest request,
                                         Type elementType, CyranoClientConfiguration configuration) {
        SseDataDeserializer deserializer = new SseDataDeserializer(configuration);
        Function<SseEventParser.SseEventData, InboundSseEvent> eventFactory =
                raw -> new InboundSseEventImpl(raw, deserializer);
        Function<InboundSseEvent, Object> mapper = mapperFor(elementType, deserializer);
        var publisher = new SseEventPublisher<>(
                () -> transport.sendForSse(request), eventFactory, mapper);
        return FlowAdapters.toPublisher(publisher);
    }

    /**
     * Publisher for a request aborted by a {@code ClientRequestFilter}: no
     * connection is opened; error statuses fail the stream, anything else
     * completes it empty.
     */
    public static Object abortedPublisher(int status) {
        SubmissionPublisher<Object> publisher = new SubmissionPublisher<>();
        if (status >= 400) {
            publisher.closeExceptionally(new WebApplicationException(
                    "HTTP " + status, CyranoLightResponse.ofStatus(status)));
        } else {
            publisher.close();
        }
        return FlowAdapters.toPublisher(publisher);
    }

    /**
     * Element mapping applied BEFORE submission into the single
     * {@code SubmissionPublisher} stage (preserves Reactive Streams
     * conformance — no ad-hoc processor):
     * {@code InboundSseEvent} passes through, {@code String} maps to
     * {@code readData()}, any other type is deserialized like
     * {@code readData(Class)}. A {@code null} mapping (comment-only event for
     * a typed publisher) drops the event.
     */
    private static Function<InboundSseEvent, Object> mapperFor(Type elementType,
                                                               SseDataDeserializer deserializer) {
        Class<?> raw = rawClass(elementType);
        if (raw == Object.class || InboundSseEvent.class.isAssignableFrom(raw)) {
            return event -> event;
        }
        if (raw == String.class) {
            return InboundSseEvent::readData;
        }
        return event -> event.isEmpty()
                ? null
                : deserializer.deserialize(event.readData(), raw, elementType,
                        MediaType.APPLICATION_JSON_TYPE);
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        return Object.class;
    }
}
