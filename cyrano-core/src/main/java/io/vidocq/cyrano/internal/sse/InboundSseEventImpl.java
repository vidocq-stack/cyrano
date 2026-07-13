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

import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.InboundSseEvent;

/**
 * Immutable {@link InboundSseEvent} received by the Cyrano SSE client —
 * MP Rest Client 4.0 §10 (reactive Publisher return types).
 *
 * <p>Typed {@code readData(...)} variants delegate to
 * {@link SseDataDeserializer} (registered {@code MessageBodyReader}s, JSON-P
 * structures, JSON-B fallback).</p>
 */
public final class InboundSseEventImpl implements InboundSseEvent {

    private final String name;
    private final String id;
    private final String comment;
    private final String data;
    private final Long retryMillis;
    private final SseDataDeserializer deserializer;

    public InboundSseEventImpl(SseEventParser.SseEventData event, SseDataDeserializer deserializer) {
        this.name = event.name();
        this.id = event.id();
        this.comment = event.comment();
        this.data = event.data();
        this.retryMillis = event.retryMillis();
        this.deserializer = deserializer;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getComment() {
        return comment;
    }

    @Override
    public long getReconnectDelay() {
        return retryMillis != null ? retryMillis : RECONNECT_NOT_SET;
    }

    @Override
    public boolean isReconnectDelaySet() {
        return retryMillis != null;
    }

    @Override
    public boolean isEmpty() {
        return data == null || data.isEmpty();
    }

    @Override
    public String readData() {
        return data;
    }

    @Override
    public <T> T readData(Class<T> type) {
        return deserializer.deserialize(data, type, type, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T readData(GenericType<T> type) {
        return (T) deserializer.deserialize(data, type.getRawType(), type.getType(), null);
    }

    @Override
    public <T> T readData(Class<T> messageType, MediaType mediaType) {
        return deserializer.deserialize(data, messageType, messageType, mediaType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T readData(GenericType<T> type, MediaType mediaType) {
        return (T) deserializer.deserialize(data, type.getRawType(), type.getType(), mediaType);
    }

    @Override
    public String toString() {
        return "InboundSseEvent[name=" + name + ", id=" + id
                + ", comment=" + comment + ", data=" + data + "]";
    }
}
