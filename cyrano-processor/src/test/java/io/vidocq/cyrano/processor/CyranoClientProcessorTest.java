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
package io.vidocq.cyrano.processor;

import io.vidocq.cyrano.spi.gen.ClientDescriptor;
import io.vidocq.cyrano.spi.gen.ClientInvoker;
import io.vidocq.cyrano.spi.gen.ClientMethodDescriptor;
import io.vidocq.cyrano.spi.gen.ClientParamDescriptor;
import io.vidocq.cyrano.spi.gen.ClientProxyFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiles fixture {@code @RegisterRestClient} interfaces WITH cyrano-processor on the
 * annotation-processor path (in-process javax.tools.JavaCompiler — same zero-dep harness
 * as cassini's CassiniResourceProcessorTest) and verifies the generated
 * {@code $$CyranoClient} sources: shape, descriptor content, invoker dispatch, the
 * ServiceLoader registration file, and the skip-on-complexity safety valve.
 */
class CyranoClientProcessorTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // Harness (shared: ProcessorTestHarness)
    // ------------------------------------------------------------------

    private ProcessorTestHarness.Compilation compileWithProcessor(File... sources) throws Exception {
        return ProcessorTestHarness.compileWithProcessor(tempDir, sources);
    }

    private File writeSource(String relativePath, String content) throws Exception {
        return ProcessorTestHarness.writeSource(tempDir, relativePath, content);
    }

    /** Recording double for the generated proxy's only runtime dependency. */
    static final class RecordingInvoker implements ClientInvoker {
        final List<Integer> indexes = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();
        boolean closed;
        Object canned;

        @Override
        public Object invoke(Object proxy, int methodIndex, Object[] invocationArgs) {
            indexes.add(methodIndex);
            args.add(invocationArgs);
            return canned;
        }

        @Override
        public void markClosed() {
            closed = true;
        }
    }

    private static ClientProxyFactory factoryOf(ProcessorTestHarness.Compilation compilation,
                                                String ifaceFqn) throws Exception {
        return compilation.factoryOf(ifaceFqn);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private File pingApiSource() throws Exception {
        return writeSource("t/PingApi.java", """
                package t;

                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.POST;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;
                import jakarta.ws.rs.QueryParam;
                import jakarta.ws.rs.DefaultValue;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

                @RegisterRestClient
                @Path("/ping")
                public interface PingApi {

                    @GET
                    @Path("/{id}")
                    String get(@PathParam("id") String id,
                               @QueryParam("verbose") @DefaultValue("false") String verbose);

                    @POST
                    long post(String body);
                }
                """);
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    void generatesClientImplementingInterfaceAndCloseable() throws Exception {
        var compilation = compileWithProcessor(pingApiSource());
        Class<?> generated = compilation.loader().loadClass("t.PingApi$$CyranoClient");
        Class<?> iface = compilation.loader().loadClass("t.PingApi");
        assertTrue(iface.isAssignableFrom(generated), "must implement the client interface");
        assertTrue(java.io.Closeable.class.isAssignableFrom(generated), "must be Closeable (spec §8.1)");
        assertTrue(java.lang.reflect.Modifier.isFinal(generated.getModifiers()));
    }

    @Test
    void descriptorCarriesLiteralMetadata() throws Exception {
        var compilation = compileWithProcessor(pingApiSource());
        ClientProxyFactory factory = factoryOf(compilation, "t.PingApi");
        assertEquals("t.PingApi", factory.clientInterface().getName());

        ClientDescriptor descriptor = factory.descriptor();
        assertEquals(2, descriptor.methods().size());

        ClientMethodDescriptor get = descriptor.methods().get(0);
        assertEquals("get", get.methodName());
        assertEquals("GET", get.httpMethod());
        assertEquals("/ping/{id}", get.pathTemplate());
        assertEquals(List.of(String.class, String.class), get.parameterTypes());
        assertEquals(new ClientParamDescriptor.Path(0, "id", null), get.params().get(0));
        assertEquals(new ClientParamDescriptor.Query(1, "verbose", "false"), get.params().get(1));

        ClientMethodDescriptor post = descriptor.methods().get(1);
        assertEquals("POST", post.httpMethod());
        assertEquals("/ping", post.pathTemplate());
        assertEquals(new ClientParamDescriptor.Body(0), post.params().get(0));
    }

    @Test
    void generatedProxy_dispatchesIndexesArgsAndBoxing() throws Exception {
        var compilation = compileWithProcessor(pingApiSource());
        ClientProxyFactory factory = factoryOf(compilation, "t.PingApi");
        var invoker = new RecordingInvoker();
        Object proxy = factory.newProxy(invoker);

        invoker.canned = "pong";
        Object r1 = proxy.getClass().getMethod("get", String.class, String.class)
                .invoke(proxy, "abc", "true");
        invoker.canned = 42L;
        Object r2 = proxy.getClass().getMethod("post", String.class).invoke(proxy, "body");

        assertEquals("pong", r1);
        assertEquals(42L, r2);
        assertEquals(List.of(0, 1), invoker.indexes);
        assertArrayEquals(new Object[] { "abc", "true" }, invoker.args.get(0));
        assertArrayEquals(new Object[] { "body" }, invoker.args.get(1));

        ((java.io.Closeable) proxy).close();
        assertTrue(invoker.closed, "close() must delegate to markClosed()");
    }

    @Test
    void registersFactoryInServiceLoaderFile() throws Exception {
        var compilation = compileWithProcessor(pingApiSource());
        Path services = compilation.outputDir().toPath()
                .resolve("META-INF/services/io.vidocq.cyrano.spi.gen.ClientProxyFactory");
        assertTrue(Files.exists(services), "processor must emit the ServiceLoader registration");
        assertTrue(Files.readString(services).contains("t.PingApi$$CyranoClient$Factory"));
    }

    @Test
    void safetyValve_skipsInterfaceWithNonRestAbstractMethod() throws Exception {
        File source = writeSource("t/Odd.java", """
                package t;

                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

                @RegisterRestClient
                @Path("/odd")
                public interface Odd {

                    @GET
                    String ok();

                    // abstract, no HTTP verb, not a sub-resource locator: a source proxy
                    // cannot be emitted for this interface — runtime fallback takes over.
                    String notRest();
                }
                """);
        var compilation = compileWithProcessor(source);
        assertThrows(ClassNotFoundException.class,
                () -> compilation.loader().loadClass("t.Odd$$CyranoClient"),
                "valve: no generated class, compilation still succeeds");
    }
}
