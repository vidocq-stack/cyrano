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
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Context for the {@link ReaderInterceptor} interceptor chain (spec JAX-RS §6.5).
 * Wraps a terminal {@link MessageBodyReader} and a list of ordered interceptors.
 */
final class CyranoReaderInterceptorContext implements ReaderInterceptorContext {

    private final Iterator<ReaderInterceptor> chain;
    private final MessageBodyReader<?> terminalReader;
    private Class<?> type;
    private Type genericType;
    private Annotation[] annotations;
    private MediaType mediaType;
    private final MultivaluedMap<String, String> headers;
    private InputStream inputStream;
    private final Map<String, Object> properties = new HashMap<>();

    CyranoReaderInterceptorContext(List<ReaderInterceptor> interceptors,
                                   MessageBodyReader<?> terminalReader,
                                   Class<?> type, Type genericType,
                                   Annotation[] annotations, MediaType mediaType,
                                   MultivaluedMap<String, String> headers,
                                   InputStream inputStream) {
        this.chain = interceptors.iterator();
        this.terminalReader = terminalReader;
        this.type = type;
        this.genericType = genericType;
        this.annotations = annotations;
        this.mediaType = mediaType;
        this.headers = headers;
        this.inputStream = inputStream;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object proceed() throws IOException, WebApplicationException {
        if (chain.hasNext()) {
            return chain.next().aroundReadFrom(this);
        } else {
            return ((MessageBodyReader) terminalReader).readFrom(type, genericType,
                    annotations, mediaType, headers, inputStream);
        }
    }

    @Override public InputStream getInputStream() { return inputStream; }
    @Override public void setInputStream(InputStream is) { this.inputStream = is; }
    @Override public MultivaluedMap<String, String> getHeaders() { return headers; }
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
