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

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Contexte {@link ClientRequestContext} (JAX-RS §6.3 + MicroProfile Rest Client §4.2)
 * exposé aux {@link jakarta.ws.rs.client.ClientRequestFilter} enregistrés via le builder.
 *
 * <p>Couvre les méthodes effectivement utilisées par le TCK MP Rest Client 4.0 :
 * URI/méthode HTTP/headers/properties/entity/abortWith/Configuration. Le
 * {@link #getClient()} renvoie {@code null} (Cyrano n'expose pas le JAX-RS {@code Client}).</p>
 *
 * <p>État mutable — les filtres peuvent modifier URI, méthode, headers, entity, ou
 * appeler {@link #abortWith(Response)} pour court-circuiter le transport.</p>
 */
final class CyranoClientRequestContext implements ClientRequestContext {

    private URI uri;
    private String method;
    private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
    private final Map<String, Object> properties = new HashMap<>();
    private final Configuration configuration;

    private Object entity;
    private Class<?> entityClass;
    private Type entityType;
    private Annotation[] entityAnnotations = new Annotation[0];
    private MediaType entityMediaType;
    private OutputStream entityStream = new ByteArrayOutputStream();

    private Response abortResponse;

    CyranoClientRequestContext(URI uri, String method, Configuration configuration) {
        this.uri = uri;
        this.method = method;
        this.configuration = configuration;
    }

    // ---- état observable pour le moteur ----

    boolean isAborted() { return abortResponse != null; }
    Response abortResponse() { return abortResponse; }

    @Override
    public void abortWith(Response response) {
        this.abortResponse = response;
    }

    // ---- properties ----
    @Override public Object getProperty(String name) { return properties.get(name); }
    @Override public Collection<String> getPropertyNames() { return Collections.unmodifiableSet(properties.keySet()); }
    @Override public void setProperty(String name, Object object) {
        if (object == null) properties.remove(name);
        else properties.put(name, object);
    }
    @Override public void removeProperty(String name) { properties.remove(name); }

    // ---- URI / method ----
    @Override public URI getUri() { return uri; }
    @Override public void setUri(URI uri) { this.uri = uri; }
    @Override public String getMethod() { return method; }
    @Override public void setMethod(String method) { this.method = method; }

    // ---- headers ----
    @Override public MultivaluedMap<String, Object> getHeaders() { return headers; }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        MultivaluedMap<String, String> sh = new MultivaluedHashMap<>();
        for (var e : headers.entrySet()) {
            List<String> values = new ArrayList<>(e.getValue().size());
            for (Object v : e.getValue()) values.add(v == null ? "" : v.toString());
            sh.put(e.getKey(), values);
        }
        return sh;
    }

    @Override
    public String getHeaderString(String name) {
        List<Object> v = headers.get(name);
        if (v == null || v.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(v.get(i) == null ? "" : v.get(i).toString());
        }
        return sb.toString();
    }

    @Override
    public boolean containsHeaderString(String name, String separatorRegex, Predicate<String> valuePredicate) {
        String hs = getHeaderString(name);
        if (hs == null) return false;
        String[] parts = separatorRegex == null ? new String[]{hs} : hs.split(separatorRegex);
        for (String p : parts) {
            if (valuePredicate.test(p.trim())) return true;
        }
        return false;
    }

    @Override public Date getDate() {
        String d = getHeaderString("Date");
        if (d == null) return null;
        try { return new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(d); }
        catch (java.text.ParseException ex) { return null; }
    }

    @Override public Locale getLanguage() {
        String l = getHeaderString("Content-Language");
        return l == null ? null : Locale.forLanguageTag(l);
    }

    @Override
    public MediaType getMediaType() {
        if (entityMediaType != null) return entityMediaType;
        String ct = getHeaderString("Content-Type");
        return ct == null ? null : MediaType.valueOf(ct);
    }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
        String accept = getHeaderString("Accept");
        if (accept == null || accept.isEmpty()) return List.of(MediaType.WILDCARD_TYPE);
        List<MediaType> r = new ArrayList<>();
        for (String s : accept.split(",")) r.add(MediaType.valueOf(s.trim()));
        return r;
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
        String al = getHeaderString("Accept-Language");
        if (al == null || al.isEmpty()) return List.of();
        List<Locale> r = new ArrayList<>();
        for (String s : al.split(",")) r.add(Locale.forLanguageTag(s.trim()));
        return r;
    }

    @Override
    public Map<String, Cookie> getCookies() {
        String c = getHeaderString("Cookie");
        if (c == null || c.isEmpty()) return Map.of();
        Map<String, Cookie> out = new LinkedHashMap<>();
        for (String pair : c.split(";")) {
            String p = pair.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String name = p.substring(0, eq).trim();
            String val = p.substring(eq + 1).trim();
            out.put(name, new Cookie.Builder(name).value(val).build());
        }
        return out;
    }

    // ---- entity ----
    @Override public boolean hasEntity() { return entity != null; }
    @Override public Object getEntity() { return entity; }
    @Override public Class<?> getEntityClass() { return entityClass; }
    @Override public Type getEntityType() { return entityType; }

    @Override
    public void setEntity(Object entity) {
        this.entity = entity;
        this.entityClass = entity == null ? null : entity.getClass();
        this.entityType = this.entityClass;
    }

    @Override
    public void setEntity(Object entity, Annotation[] annotations, MediaType mediaType) {
        this.entity = entity;
        this.entityClass = entity == null ? null : entity.getClass();
        this.entityType = this.entityClass;
        this.entityAnnotations = annotations == null ? new Annotation[0] : annotations.clone();
        this.entityMediaType = mediaType;
        if (mediaType != null) headers.putSingle("Content-Type", mediaType.toString());
    }

    /** Setter interne — utilisé par le handler pour pré-remplir avant les filtres. */
    void setEntityInternal(Object entity, Class<?> cls, Type type) {
        this.entity = entity;
        this.entityClass = cls;
        this.entityType = type;
    }

    @Override public Annotation[] getEntityAnnotations() { return entityAnnotations.clone(); }
    @Override public OutputStream getEntityStream() { return entityStream; }
    @Override public void setEntityStream(OutputStream outputStream) { this.entityStream = outputStream; }

    // ---- client / configuration ----
    @Override public Client getClient() { return null; }
    @Override public Configuration getConfiguration() { return configuration; }
}

