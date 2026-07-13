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
import io.vidocq.cyrano.internal.JsonbHolder;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.ext.MessageBodyReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * Converts the raw {@code data} payload of an SSE event into a typed value —
 * MP Rest Client 4.0 §10 ({@code InboundSseEvent.readData(...)} on reactive
 * Publisher return types).
 *
 * <p>Resolution order (mirrors {@code CyranoInvocationHandler.mapResponse}):
 * {@code String} → raw data; {@code jakarta.json} structure types → JSON-P
 * reader; a {@link MessageBodyReader} registered on the client configuration;
 * otherwise the shared JSON-B fallback (champollion at runtime).</p>
 */
public final class SseDataDeserializer {

    private final CyranoClientConfiguration configuration;

    public SseDataDeserializer(CyranoClientConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * Deserializes {@code data} into {@code rawType}/{@code genericType}.
     *
     * @param data        the raw event data (may be {@code null} → {@code null} result)
     * @param rawType     target raw class
     * @param genericType full generic target type (equal to {@code rawType} if non-generic)
     * @param mediaType   media type hint for {@link MessageBodyReader} lookup
     *                    ({@code null} → {@code application/json})
     */
    @SuppressWarnings("unchecked")
    public <T> T deserialize(String data, Class<T> rawType, Type genericType, MediaType mediaType) {
        if (data == null) {
            return null;
        }
        if (rawType == String.class) {
            return (T) data;
        }
        MediaType mt = mediaType != null ? mediaType : MediaType.APPLICATION_JSON_TYPE;
        if (rawType == JsonObject.class) {
            try (var reader = Json.createReader(new StringReader(data))) {
                return (T) reader.readObject();
            }
        }
        if (rawType == JsonArray.class) {
            try (var reader = Json.createReader(new StringReader(data))) {
                return (T) reader.readArray();
            }
        }
        if (rawType == JsonValue.class) {
            try (var reader = Json.createReader(new StringReader(data))) {
                return (T) reader.readValue();
            }
        }
        MessageBodyReader<T> reader = findMessageBodyReader(rawType, genericType, mt);
        if (reader != null) {
            try {
                return reader.readFrom(rawType, genericType, new Annotation[0], mt,
                        new MultivaluedHashMap<>(),
                        new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new ProcessingException(e);
            }
        }
        //JSON-B fallback — same path as the POJO mapping in the invocation handler.
        return (T) JsonbHolder.get().fromJson(data, genericType);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> MessageBodyReader<T> findMessageBodyReader(Class<T> rawType, Type genericType,
                                                           MediaType mediaType) {
        if (configuration == null) {
            return null;
        }
        Annotation[] none = new Annotation[0];
        for (Object inst : configuration.getInstances()) {
            if (inst instanceof MessageBodyReader r
                    && (r.isReadable(rawType, genericType, none, mediaType)
                        || r.isReadable(rawType, genericType, none, MediaType.WILDCARD_TYPE))) {
                return (MessageBodyReader<T>) r;
            }
        }
        return null;
    }
}
