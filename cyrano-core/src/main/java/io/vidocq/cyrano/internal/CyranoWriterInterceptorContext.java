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

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Contexte pour la chaîne d'intercepteurs {@link WriterInterceptor} (spec JAX-RS §6.5).
 * Wraps un {@link MessageBodyWriter} terminal et une liste d'intercepteurs ordonnés.
 */
final class CyranoWriterInterceptorContext implements WriterInterceptorContext {

    private final Iterator<WriterInterceptor> chain;
    private final MessageBodyWriter<?> terminalWriter;
    private Object entity;
    private Class<?> type;
    private Type genericType;
    private Annotation[] annotations;
    private MediaType mediaType;
    private final MultivaluedMap<String, Object> headers;
    private OutputStream outputStream;
    private final Map<String, Object> properties = new HashMap<>();

    CyranoWriterInterceptorContext(List<WriterInterceptor> interceptors,
                                   MessageBodyWriter<?> terminalWriter,
                                   Object entity, Class<?> type, Type genericType,
                                   Annotation[] annotations, MediaType mediaType,
                                   MultivaluedMap<String, Object> headers,
                                   OutputStream outputStream) {
        this.chain = interceptors.iterator();
        this.terminalWriter = terminalWriter;
        this.entity = entity;
        this.type = type;
        this.genericType = genericType;
        this.annotations = annotations;
        this.mediaType = mediaType;
        this.headers = headers;
        this.outputStream = outputStream;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void proceed() throws IOException, WebApplicationException {
        if (chain.hasNext()) {
            chain.next().aroundWriteTo(this);
        } else {
            ((MessageBodyWriter) terminalWriter).writeTo(entity, type, genericType,
                    annotations, mediaType, headers, outputStream);
        }
    }

    @Override public Object getEntity() { return entity; }
    @Override public void setEntity(Object entity) { this.entity = entity; }
    @Override public OutputStream getOutputStream() { return outputStream; }
    @Override public void setOutputStream(OutputStream os) { this.outputStream = os; }
    @Override public MultivaluedMap<String, Object> getHeaders() { return headers; }
    @Override public Object getProperty(String name) { return properties.get(name); }
    @Override public Collection<String> getPropertyNames() { return properties.keySet(); }
    @Override public void setProperty(String name, Object object) { properties.put(name, object); }
    @Override public void removeProperty(String name) { properties.remove(name); }
    @Override public Annotation[] getAnnotations() { return annotations; }
    @Override public void setAnnotations(Annotation[] annotations) { this.annotations = annotations; }
    @Override public Class<?> getType() { return type; }
    @Override public void setType(Class<?> type) { this.type = type; }
    @Override public Type getGenericType() { return genericType; }
    @Override public void setGenericType(Type genericType) { this.genericType = genericType; }
    @Override public MediaType getMediaType() { return mediaType; }
    @Override public void setMediaType(MediaType mediaType) { this.mediaType = mediaType; }
}
