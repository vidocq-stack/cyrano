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

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.ReaderInterceptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.io.StringReader;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Minimal implementation of {@link Response} (M1) — avoids implementation dependence on
 * {@code RuntimeDelegate} (Cassini) for the first integration tests. Will be
 * replaced in M2 by using {@link Response#ok()} and similar once Cassini is
 * available at runtime.
 *
 * <p>Only covers methods that may be called by M1 tests:
 * {@link #getStatus()}, {@link #readEntity(Class)} for {@link String}.</p>
 */
final class CyranoLightResponse extends Response {

    private final int status;
    private final String body;
    private final URI location;
    private final MultivaluedMap<String, Object> headers;
    private final MultivaluedMap<String, String> stringHeaders;
    /** Nullable — set when the response carries registered providers for readEntity(). */
    private final CyranoClientConfiguration configuration;

    private CyranoLightResponse(int status, String body, URI location,
                                MultivaluedMap<String, Object> headers,
                                MultivaluedMap<String, String> stringHeaders,
                                CyranoClientConfiguration configuration) {
        this.status = status;
        this.body = body;
        this.location = location;
        this.headers = headers;
        this.stringHeaders = stringHeaders;
        this.configuration = configuration;
    }

    static CyranoLightResponse of(HttpResponse<String> resp) {
        URI loc = resp.headers().firstValue("Location").map(URI::create).orElse(null);
        MultivaluedMap<String, Object> h = new MultivaluedHashMap<>();
        MultivaluedMap<String, String> hs = new MultivaluedHashMap<>();
        resp.headers().map().forEach((k, vs) -> {
            for (String v : vs) {
                h.add(k, v);
                hs.add(k, v);
            }
        });
        return new CyranoLightResponse(resp.statusCode(), resp.body(), loc, h, hs, null);
    }

    /**
     * Builds an immutable {@link Response} view from a {@link CyranoClientResponseContext}
     * (post-pipeline filters) and body string already extracted — used by
     * {@code ResponseExceptionMapper} and the default mapper (spec §8).
     */
    static CyranoLightResponse of(CyranoClientResponseContext ctx, String body) {
        return of(ctx, body, null);
    }

    /**
     * Variant with configuration — enables readEntity() to use registered MessageBodyReaders
     * and ReaderInterceptors (spec §4.2 §6.5).
     */
    static CyranoLightResponse of(CyranoClientResponseContext ctx, String body,
                                   CyranoClientConfiguration configuration) {
        MultivaluedMap<String, Object> h = new MultivaluedHashMap<>();
        MultivaluedMap<String, String> hs = new MultivaluedHashMap<>();
        ctx.getHeaders().forEach((k, vs) -> {
            for (String v : vs) {
                h.add(k, v);
                hs.add(k, v);
            }
        });
        URI loc = ctx.getLocation();
        return new CyranoLightResponse(ctx.getStatus(), body, loc, h, hs, configuration);
    }

    @Override public int getStatus() { return status; }
    @Override public StatusType getStatusInfo() { return Status.fromStatusCode(status); }
    @Override public Object getEntity() { return body; }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> T readEntity(Class<T> entityType) {
        if (configuration != null) {
            MessageBodyReader reader = findReader(entityType);
            if (reader != null) {
                try {
                    InputStream is = new ByteArrayInputStream(
                            body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0]);
                    List<ReaderInterceptor> interceptors = configuration.getReaderInterceptors();
                    if (!interceptors.isEmpty()) {
                        return (T) new CyranoReaderInterceptorContext(interceptors, reader,
                                entityType, entityType, new Annotation[0],
                                getMediaType(), new MultivaluedHashMap<>(), is).proceed();
                    } else {
                        return (T) reader.readFrom(entityType, entityType, new Annotation[0],
                                getMediaType(), new MultivaluedHashMap<>(), is);
                    }
                } catch (IOException e) {
                    throw new ProcessingException(e);
                }
            }
        }
        if (entityType == String.class) return (T) body;
        if (entityType == JsonArray.class) {
            return (T) Json.createReader(new StringReader(body == null ? "" : body)).readArray();
        }
        if (entityType == JsonObject.class) {
            return (T) Json.createReader(new StringReader(body == null ? "" : body)).readObject();
        }
        throw new UnsupportedOperationException("readEntity(" + entityType.getSimpleName()
                + ") requires a registered MessageBodyReader");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private MessageBodyReader findReader(Class<?> entityType) {
        if (configuration == null) return null;
        MediaType wildcard = MediaType.WILDCARD_TYPE;
        for (Object inst : configuration.getInstances()) {
            if (inst instanceof MessageBodyReader r) {
                if (r.isReadable(entityType, entityType, new Annotation[0], wildcard)) return r;
            }
        }
        return null;
    }
    @Override public <T> T readEntity(jakarta.ws.rs.core.GenericType<T> entityType) { throw nope(); }
    @Override public <T> T readEntity(Class<T> entityType, Annotation[] annotations) { throw nope(); }
    @Override public <T> T readEntity(jakarta.ws.rs.core.GenericType<T> entityType, Annotation[] annotations) { throw nope(); }
    @Override public boolean hasEntity() { return body != null && !body.isEmpty(); }
    @Override public boolean bufferEntity() { return false; }
    @Override public void close() { /* no-op */ }
    @Override public MediaType getMediaType() { return MediaType.WILDCARD_TYPE; }
    @Override public Locale getLanguage() { return null; }
    @Override public int getLength() { return body == null ? -1 : body.length(); }
    @Override public Set<String> getAllowedMethods() { return Set.of(); }
    @Override public Map<String, NewCookie> getCookies() { return Map.of(); }
    @Override public jakarta.ws.rs.core.EntityTag getEntityTag() { return null; }
    @Override public Date getDate() { return null; }
    @Override public Date getLastModified() { return null; }
    @Override public URI getLocation() { return location; }
    @Override public Set<jakarta.ws.rs.core.Link> getLinks() { return Set.of(); }
    @Override public boolean hasLink(String relation) { return false; }
    @Override public jakarta.ws.rs.core.Link getLink(String relation) { return null; }
    @Override public jakarta.ws.rs.core.Link.Builder getLinkBuilder(String relation) { throw nope(); }
    @Override public MultivaluedMap<String, Object> getMetadata() { return headers; }
    @Override public MultivaluedMap<String, Object> getHeaders() { return headers; }
    @Override public MultivaluedMap<String, String> getStringHeaders() { return stringHeaders; }
    @Override public String getHeaderString(String name) {
        var v = headerValues(name);
        return v == null || v.isEmpty() ? null : String.join(",", v);
    }

    private List<String> headerValues(String name) {
        List<String> exact = stringHeaders.get(name);
        if (exact != null) return exact;
        for (var entry : stringHeaders.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    private static UnsupportedOperationException nope() {
        return new UnsupportedOperationException("M1 minimal: method not supported");
    }
}
