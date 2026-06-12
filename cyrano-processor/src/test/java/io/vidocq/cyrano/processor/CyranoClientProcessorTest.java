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

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    // Harness
    // ------------------------------------------------------------------

    private record Compilation(URLClassLoader loader, File outputDir,
                               List<javax.tools.Diagnostic<? extends JavaFileObject>> diagnostics) {
    }

    private Compilation compileWithProcessor(File... sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "javax.tools.JavaCompiler not available in this JDK");

        File outputDir = Files.createDirectories(tempDir.resolve("classes-" + System.nanoTime())).toFile();

        List<File> cpFiles = new ArrayList<>();
        String cpProp = System.getProperty("java.class.path", "");
        for (String entry : cpProp.split(File.pathSeparator)) {
            if (!entry.isBlank()) cpFiles.add(new File(entry));
        }
        ClassLoader cl = getClass().getClassLoader();
        while (cl != null) {
            if (cl instanceof URLClassLoader ucl) {
                for (java.net.URL url : ucl.getURLs()) {
                    if ("file".equals(url.getProtocol())) cpFiles.add(new File(url.toURI()));
                }
            }
            cl = cl.getParent();
        }
        ModuleLayer layer = getClass().getModule().getLayer();
        if (layer != null) {
            layer.configuration().modules().forEach(rm -> rm.reference().location().ifPresent(uri -> {
                if ("file".equals(uri.getScheme())) cpFiles.add(new File(uri));
            }));
        }
        List<File> dedupCp = cpFiles.stream().distinct().filter(File::exists).toList();

        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(diags, Locale.ROOT, null);
        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir));
        fm.setLocation(StandardLocation.CLASS_PATH, dedupCp);
        fm.setLocation(StandardLocation.ANNOTATION_PROCESSOR_PATH, dedupCp);

        Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(sources);
        List<String> options = List.of("--release", "25", "-proc:full");
        boolean success = compiler.getTask(null, fm, diags, options, null, units).call();
        if (!success) {
            StringBuilder sb = new StringBuilder("Compilation failed:\n");
            for (var d : diags.getDiagnostics()) {
                sb.append(d.getKind()).append(": ").append(d.getMessage(Locale.ROOT)).append('\n');
            }
            fail(sb.toString());
        }
        fm.close();
        return new Compilation(
                new URLClassLoader(new java.net.URL[] { outputDir.toURI().toURL() }, getClass().getClassLoader()),
                outputDir, diags.getDiagnostics());
    }

    private File writeSource(String relativePath, String content) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file.toFile();
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

    private static ClientProxyFactory factoryOf(Compilation compilation, String ifaceFqn) throws Exception {
        Class<?> factoryClass = compilation.loader().loadClass(ifaceFqn + "$$CyranoClient$Factory");
        return (ClientProxyFactory) factoryClass.getDeclaredConstructor().newInstance();
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
