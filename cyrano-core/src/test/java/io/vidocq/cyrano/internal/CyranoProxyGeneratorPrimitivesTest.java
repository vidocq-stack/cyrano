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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CyranoProxyGenerator}: boxing of every primitive parameter, unboxing of every primitive
 * return type, exception propagation of the generated methods, and idempotent definition (runtime
 * fallback of MP Rest Client 4.0 §2 proxies).
 */
class CyranoProxyGeneratorPrimitivesTest {

    private static final String V = "v";

    @Path("/prim")
    public interface Primitives {
        @GET
        int intValue(@QueryParam(V) int v);

        @GET
        long longValue(@QueryParam(V) long v);

        @GET
        double doubleValue(@QueryParam(V) double v);

        @GET
        float floatValue(@QueryParam(V) float v);

        @GET
        boolean booleanValue(@QueryParam(V) boolean v);

        @GET
        short shortValue(@QueryParam(V) short v);

        @GET
        byte byteValue(@QueryParam(V) byte v);

        @GET
        char charValue(@QueryParam(V) char v);

        @GET
        String mixed(@QueryParam("a") int a, @QueryParam("b") long b, @QueryParam("c") double c,
                     @QueryParam("d") float d, @QueryParam("e") boolean e, @QueryParam("f") short f,
                     @QueryParam("g") byte g, @QueryParam("h") char h, @QueryParam("i") String i);

        @GET
        void fire(@QueryParam(V) int v);
    }

    @Path("/wrap")
    public interface Wrappers {
        @GET
        Integer intValue(@QueryParam(V) Integer v);

        @GET
        Long longValue(@QueryParam(V) Long v);

        @GET
        Double doubleValue(@QueryParam(V) Double v);

        @GET
        Float floatValue(@QueryParam(V) Float v);

        @GET
        Boolean booleanValue(@QueryParam(V) Boolean v);

        @GET
        Short shortValue(@QueryParam(V) Short v);

        @GET
        Byte byteValue(@QueryParam(V) Byte v);

        @GET
        Character charValue(@QueryParam(V) String v);
    }

    @Path("/once")
    public interface DefinedTwice {
        @GET
        String get();
    }

    /** Answers each call without HTTP: the first argument, or the joined arguments. */
    private static final class EchoHandler extends CyranoInvocationHandler {
        private Throwable toThrow;

        EchoHandler() {
            super(URI.create("http://localhost"), List.of(), new CyranoHttpTransport());
        }

        @Override
        public Object invoke(Object proxy, int methodIndex, Object[] args) throws Exception {
            if (toThrow instanceof Exception e) throw e;
            if (toThrow instanceof Error e) throw e;
            if (args.length == 0) return null;
            return args.length == 1 ? args[0] : Arrays.toString(args);
        }
    }

    private static <T> T echoProxy(Class<T> iface, EchoHandler handler) {
        return CyranoProxyGenerator.instantiate(CyranoProxyCache.getOrGenerate(iface).proxyClass(), handler);
    }

    @Test
    void every_primitive_parameter_is_boxed_and_every_primitive_return_unboxed() {
        Primitives p = echoProxy(Primitives.class, new EchoHandler());

        assertEquals(7, p.intValue(7));
        assertEquals(Long.MAX_VALUE, p.longValue(Long.MAX_VALUE));
        assertEquals(2.5d, p.doubleValue(2.5d));
        assertEquals(1.25f, p.floatValue(1.25f));
        assertTrue(p.booleanValue(true));
        assertEquals((short) 3, p.shortValue((short) 3));
        assertEquals((byte) 4, p.byteValue((byte) 4));
        assertEquals('z', p.charValue('z'));
        assertEquals("[1, 2, 3.0, 4.0, false, 5, 6, c, s]",
                p.mixed(1, 2L, 3d, 4f, false, (short) 5, (byte) 6, 'c', "s"));
        p.fire(1);
        assertTrue(Primitives.class.isInstance(p));
    }

    @Test
    void generated_methods_rethrow_unchecked_and_wrap_checked_exceptions() {
        EchoHandler handler = new EchoHandler();
        Primitives p = echoProxy(Primitives.class, handler);

        handler.toThrow = new IllegalArgumentException(V);
        assertThrows(IllegalArgumentException.class, () -> p.intValue(1));

        handler.toThrow = new StackOverflowError(V);
        assertThrows(StackOverflowError.class, () -> p.longValue(1L));

        handler.toThrow = new Exception(V);
        RuntimeException wrapped = assertThrows(RuntimeException.class, () -> p.doubleValue(1d));
        assertEquals(Exception.class, wrapped.getCause().getClass());
    }

    @Test
    void primitive_bodies_are_parsed_from_the_http_response_spec_section3() {
        try (RecordingHttpServer server = new RecordingHttpServer().echoQuery()) {
            Primitives p = RestClientBuilder.newBuilder().baseUri(server.baseUri()).build(Primitives.class);
            Wrappers w = RestClientBuilder.newBuilder().baseUri(server.baseUri()).build(Wrappers.class);

            assertEquals(-9, p.intValue(-9));
            assertEquals(9_000_000_000L, p.longValue(9_000_000_000L));
            assertEquals(0.5d, p.doubleValue(0.5d));
            assertEquals(0.25f, p.floatValue(0.25f));
            assertFalse(p.booleanValue(false));
            assertEquals((short) 12, p.shortValue((short) 12));
            assertEquals((byte) 13, p.byteValue((byte) 13));
            assertEquals('q', p.charValue('q'));

            assertEquals(1, w.intValue(1));
            assertEquals(2L, w.longValue(2L));
            assertEquals(3d, w.doubleValue(3d));
            assertEquals(4f, w.floatValue(4f));
            assertEquals(Boolean.TRUE, w.booleanValue(true));
            assertEquals((short) 5, w.shortValue((short) 5));
            assertEquals((byte) 6, w.byteValue((byte) 6));
            assertEquals('x', w.charValue("xyz"));
            assertEquals('\0', w.charValue(""));
        }
    }

    @Test
    void defining_an_already_defined_proxy_returns_the_existing_class() {
        Map<java.lang.reflect.Method, RequestSpec> specs = CyranoInterfaceScanner.scan(DefinedTwice.class);

        Class<?> first = CyranoProxyGenerator.generate(DefinedTwice.class, specs);
        Class<?> second = CyranoProxyGenerator.generate(DefinedTwice.class, specs);

        assertSame(first, second);
    }

    @Test
    void instantiating_a_class_that_is_not_a_proxy_fails() {
        EchoHandler handler = new EchoHandler();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CyranoProxyGenerator.instantiate(String.class, handler));
        assertInstanceOf(NoSuchMethodException.class, e.getCause());
    }
}
