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
 * Implémentation minimale de {@link Response} (M1) — évite la dépendance à une impl
 * {@code RuntimeDelegate} (Cassini) pour les premiers tests d'intégration. Sera
 * remplacée en M2 par l'utilisation de {@link Response#ok()} et consorts une fois
 * Cassini disponible en runtime.
 *
 * <p>Couvre uniquement les méthodes susceptibles d'être appelées par les tests M1 :
 * {@link #getStatus()}, {@link #readEntity(Class)} pour {@link String}.</p>
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
     * Construit une vue {@link Response} immuable à partir d'un {@link CyranoClientResponseContext}
     * (post-pipeline filtres) et de la chaîne de body déjà extraite — utilisée par les
     * {@code ResponseExceptionMapper} et le default mapper (spec §8).
     */
    static CyranoLightResponse of(CyranoClientResponseContext ctx, String body) {
        return of(ctx, body, null);
    }

    /**
     * Variant with configuration — enables readEntity() to use registered MessageBodyReaders
     * and ReaderInterceptors (spec §4.2, §6.5).
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
        return new UnsupportedOperationException("M1 minimal : méthode non supportée");
    }
}

