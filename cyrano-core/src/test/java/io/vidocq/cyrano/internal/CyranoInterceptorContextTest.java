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

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CyranoWriterInterceptorContext} and {@link CyranoReaderInterceptorContext}: the
 * entity interceptor chains of a client (MP Rest Client 4.0 §4.2, Jakarta REST §6.4).
 */
class CyranoInterceptorContextTest {

    private static final String PREFIX = "prefix";

    /** Writes {@code toString()} of the entity. */
    private static final MessageBodyWriter<Object> TO_STRING_WRITER = new MessageBodyWriter<>() {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return true;
        }

        @Override
        public void writeTo(Object o, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write((o + "|" + type.getSimpleName() + "|" + httpHeaders.getFirst("X-Seen"))
                    .getBytes(StandardCharsets.UTF_8));
        }
    };

    /** Reads the stream as a string, suffixed with the requested type. */
    private static final MessageBodyReader<Object> STRING_READER = new MessageBodyReader<>() {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return true;
        }

        @Override
        public Object readFrom(Class<Object> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                               MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            return new String(entityStream.readAllBytes(), StandardCharsets.UTF_8) + "|" + type.getSimpleName();
        }
    };

    @Test
    void writer_chain_runs_the_interceptors_in_order_then_the_writer() throws IOException {
        WriterInterceptor first = ctx -> {
            ctx.getHeaders().putSingle("X-Seen", "first");
            ctx.setEntity(ctx.getEntity() + "-1");
            ctx.proceed();
        };
        WriterInterceptor second = ctx -> {
            ctx.setEntity(ctx.getEntity() + "-2");
            ctx.setType(Integer.class);
            ctx.proceed();
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        new CyranoWriterInterceptorContext(List.of(first, second), TO_STRING_WRITER, "body", String.class,
                String.class, new Annotation[0], MediaType.WILDCARD_TYPE, new MultivaluedHashMap<>(), out).proceed();

        assertEquals("body-1-2|Integer|first", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void writer_context_exposes_and_replaces_its_state() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        CyranoWriterInterceptorContext ctx = new CyranoWriterInterceptorContext(List.of(), TO_STRING_WRITER,
                "body", String.class, String.class, new Annotation[0], MediaType.WILDCARD_TYPE, headers, out);

        assertSame(out, ctx.getOutputStream());
        assertSame(headers, ctx.getHeaders());
        assertEquals(String.class, ctx.getGenericType());
        assertEquals(MediaType.WILDCARD_TYPE, ctx.getMediaType());

        ByteArrayOutputStream other = new ByteArrayOutputStream();
        Annotation[] annotations = Test.class.getAnnotations();
        ctx.setOutputStream(other);
        ctx.setAnnotations(annotations);
        ctx.setGenericType(Integer.class);
        ctx.setMediaType(MediaType.APPLICATION_JSON_TYPE);
        ctx.setProperty(PREFIX, "p");

        assertSame(other, ctx.getOutputStream());
        assertArrayEquals(annotations, ctx.getAnnotations());
        assertEquals(Integer.class, ctx.getGenericType());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, ctx.getMediaType());
        assertEquals("p", ctx.getProperty(PREFIX));
        assertTrue(ctx.getPropertyNames().contains(PREFIX));

        ctx.removeProperty(PREFIX);
        assertNull(ctx.getProperty(PREFIX));
    }

    @Test
    void reader_chain_runs_the_interceptors_in_order_then_the_reader() throws IOException {
        ReaderInterceptor first = ctx -> {
            ctx.setInputStream(new ByteArrayInputStream("replaced".getBytes(StandardCharsets.UTF_8)));
            return "<" + ctx.proceed() + ">";
        };
        ReaderInterceptor second = ctx -> {
            ctx.setType(Integer.class);
            return ctx.proceed();
        };
        InputStream in = new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8));

        Object read = new CyranoReaderInterceptorContext(List.of(first, second), STRING_READER, String.class,
                String.class, new Annotation[0], MediaType.WILDCARD_TYPE, new MultivaluedHashMap<>(), in).proceed();

        assertEquals("<replaced|Integer>", read);
    }

    @Test
    void reader_context_exposes_and_replaces_its_state() {
        InputStream in = new ByteArrayInputStream(new byte[0]);
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        CyranoReaderInterceptorContext ctx = new CyranoReaderInterceptorContext(List.of(), STRING_READER,
                String.class, String.class, new Annotation[0], MediaType.WILDCARD_TYPE, headers, in);

        assertSame(in, ctx.getInputStream());
        assertSame(headers, ctx.getHeaders());
        assertEquals(String.class, ctx.getType());
        assertEquals(String.class, ctx.getGenericType());
        assertEquals(MediaType.WILDCARD_TYPE, ctx.getMediaType());

        Annotation[] annotations = Test.class.getAnnotations();
        ctx.setAnnotations(annotations);
        ctx.setGenericType(Integer.class);
        ctx.setMediaType(MediaType.TEXT_PLAIN_TYPE);
        ctx.setProperty(PREFIX, "p");

        assertArrayEquals(annotations, ctx.getAnnotations());
        assertEquals(Integer.class, ctx.getGenericType());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, ctx.getMediaType());
        assertEquals("p", ctx.getProperty(PREFIX));
        assertTrue(ctx.getPropertyNames().contains(PREFIX));

        ctx.removeProperty(PREFIX);
        assertNull(ctx.getProperty(PREFIX));
    }
}
