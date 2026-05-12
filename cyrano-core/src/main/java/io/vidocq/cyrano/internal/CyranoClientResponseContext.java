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

import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Contexte {@link ClientResponseContext} (JAX-RS §6.3) exposé aux
 * {@link jakarta.ws.rs.client.ClientResponseFilter} après réception (ou après abort
 * via {@code abortWith}). État mutable — un filtre peut changer le statut ou les headers.
 */
final class CyranoClientResponseContext implements ClientResponseContext {

    private int status;
    private Response.StatusType statusInfo;
    private final MultivaluedMap<String, String> headers;
    private InputStream entityStream;

    /** Construit le contexte depuis une réponse HTTP réelle. */
    static CyranoClientResponseContext of(java.net.http.HttpResponse<String> resp) {
        MultivaluedMap<String, String> h = new MultivaluedHashMap<>();
        resp.headers().map().forEach((k, vs) -> {
            for (String v : vs) h.add(k, v);
        });
        byte[] body = resp.body() == null ? new byte[0] : resp.body().getBytes(StandardCharsets.UTF_8);
        return new CyranoClientResponseContext(resp.statusCode(), h, new ByteArrayInputStream(body));
    }

    /** Construit le contexte depuis une réponse abortée par un filtre. */
    static CyranoClientResponseContext fromAbort(Response r) {
        MultivaluedMap<String, String> h = new MultivaluedHashMap<>();
        r.getStringHeaders().forEach(h::addAll);
        InputStream stream;
        Object entity = r.hasEntity() ? r.getEntity() : null;
        if (entity == null) stream = new ByteArrayInputStream(new byte[0]);
        else if (entity instanceof byte[] b) stream = new ByteArrayInputStream(b);
        else if (entity instanceof InputStream is) stream = is;
        else stream = new ByteArrayInputStream(entity.toString().getBytes(StandardCharsets.UTF_8));
        return new CyranoClientResponseContext(r.getStatus(), h, stream);
    }

    private CyranoClientResponseContext(int status, MultivaluedMap<String, String> headers, InputStream stream) {
        this.status = status;
        this.statusInfo = Response.Status.fromStatusCode(status);
        this.headers = headers;
        this.entityStream = stream;
    }

    @Override public int getStatus() { return status; }
    @Override public void setStatus(int code) {
        this.status = code;
        this.statusInfo = Response.Status.fromStatusCode(code);
    }
    @Override public Response.StatusType getStatusInfo() { return statusInfo; }
    @Override public void setStatusInfo(Response.StatusType statusInfo) {
        this.statusInfo = statusInfo;
        this.status = statusInfo.getStatusCode();
    }

    @Override public MultivaluedMap<String, String> getHeaders() { return headers; }

    @Override
    public String getHeaderString(String name) {
        var v = headerValues(name);
        return v == null || v.isEmpty() ? null : String.join(",", v);
    }

    private java.util.List<String> headerValues(String name) {
        java.util.List<String> exact = headers.get(name);
        if (exact != null) return exact;
        for (var entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    @Override
    public boolean containsHeaderString(String name, String separatorRegex, Predicate<String> valuePredicate) {
        String hs = getHeaderString(name);
        if (hs == null) return false;
        String[] parts = separatorRegex == null ? new String[]{hs} : hs.split(separatorRegex);
        for (String p : parts) if (valuePredicate.test(p.trim())) return true;
        return false;
    }

    @Override public Set<String> getAllowedMethods() {
        String v = getHeaderString("Allow");
        if (v == null) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String s : v.split(",")) out.add(s.trim().toUpperCase(Locale.ROOT));
        return out;
    }
    @Override public Date getDate() { return null; }
    @Override public Locale getLanguage() {
        String l = getHeaderString("Content-Language");
        return l == null ? null : Locale.forLanguageTag(l);
    }
    @Override public int getLength() {
        String cl = getHeaderString("Content-Length");
        try { return cl == null ? -1 : Integer.parseInt(cl); } catch (NumberFormatException e) { return -1; }
    }
    @Override public MediaType getMediaType() {
        String ct = getHeaderString("Content-Type");
        return ct == null ? null : MediaType.valueOf(ct);
    }
    @Override public Map<String, NewCookie> getCookies() { return Map.of(); }
    @Override public EntityTag getEntityTag() { return null; }
    @Override public Date getLastModified() { return null; }
    @Override public URI getLocation() {
        String l = getHeaderString("Location");
        return l == null ? null : URI.create(l);
    }
    @Override public Set<Link> getLinks() { return Set.of(); }
    @Override public boolean hasLink(String relation) { return false; }
    @Override public Link getLink(String relation) { return null; }
    @Override public Link.Builder getLinkBuilder(String relation) { return null; }

    @Override
    public boolean hasEntity() {
        try { return entityStream != null && entityStream.available() > 0; }
        catch (java.io.IOException e) { return false; }
    }

    @Override public InputStream getEntityStream() { return entityStream; }
    @Override public void setEntityStream(InputStream input) { this.entityStream = input; }

    /** Lit la totalité du body — utilisé par le moteur pour reconstruire la chaîne de réponse. */
    String readBodyAsString() {
        try {
            return new String(entityStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }
}

