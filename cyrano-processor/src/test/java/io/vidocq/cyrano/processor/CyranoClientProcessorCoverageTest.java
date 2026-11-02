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
import io.vidocq.cyrano.spi.gen.DynamicHeaderDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Iteration battery for the processor (plan step 5): bean params, dynamic and
 * type-level headers, media-type overrides, CompletionStage returns, primitives and
 * void, sub-resource locator transitive generation, varargs, and the remaining
 * safety-valve cases (generic interface, path placeholder mismatch, duplicate
 * headers, close() collision).
 */
class CyranoClientProcessorCoverageTest {

    @TempDir
    Path tempDir;

    private ProcessorTestHarness.Compilation compile(File... sources) throws Exception {
        return ProcessorTestHarness.compileWithProcessor(tempDir, sources);
    }

    private File writeSource(String relativePath, String content) throws Exception {
        return ProcessorTestHarness.writeSource(tempDir, relativePath, content);
    }

    private static ClientProxyFactory factoryOf(ProcessorTestHarness.Compilation c,
                                                String ifaceFqn) throws Exception {
        return c.factoryOf(ifaceFqn);
    }

    private static final class StubInvoker implements ClientInvoker {
        Object canned;
        int lastIndex = -1;
        Object[] lastArgs;

        @Override
        public Object invoke(Object proxy, int methodIndex, Object[] args) {
            lastIndex = methodIndex;
            lastArgs = args;
            return canned;
        }

        @Override
        public void markClosed() {
            // the stub has no resource to release
        }
    }

    // ------------------------------------------------------------------
    // Rich descriptor coverage
    // ------------------------------------------------------------------

    @Test
    void beanParams_headersAndMediaTypes_fullDescriptor() throws Exception {
        File bean = writeSource("t/Filter.java", """
                package t;
                import jakarta.ws.rs.QueryParam;
                import jakarta.ws.rs.HeaderParam;
                import jakarta.ws.rs.DefaultValue;

                public class Filter extends BaseFilter {
                    @QueryParam("q") @DefaultValue("*") public String q;
                }
                """);
        File baseBean = writeSource("t/BaseFilter.java", """
                package t;
                import jakarta.ws.rs.HeaderParam;

                public class BaseFilter {
                    @HeaderParam("X-Base") public String base;
                }
                """);
        File api = writeSource("t/SearchApi.java", """
                package t;
                import jakarta.ws.rs.BeanParam;
                import jakarta.ws.rs.Consumes;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.Produces;
                import java.util.concurrent.CompletionStage;
                import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

                @RegisterRestClient
                @Path("search")
                @Consumes("application/json")
                @Produces("application/json")
                @ClientHeaderParam(name = "X-Tenant", value = "acme")
                public interface SearchApi {

                    @GET
                    @Produces("text/plain")
                    @ClientHeaderParam(name = "X-Auth", value = "{computeAuth}", required = false)
                    CompletionStage<String> find(@BeanParam Filter filter);

                    default String computeAuth() {
                        return "token";
                    }
                }
                """);
        var compilation = compile(baseBean, bean, api);
        ClientDescriptor descriptor = factoryOf(compilation, "t.SearchApi").descriptor();

        assertEquals(1, descriptor.methods().size(), "default compute method must not be a descriptor entry");
        ClientMethodDescriptor find = descriptor.methods().get(0);
        assertEquals("/search", find.pathTemplate(), "leading slash added, type-level path only");
        assertEquals(List.of("application/json"), find.consumes());
        assertEquals(List.of("text/plain"), find.produces(), "method-level @Produces overrides type level");
        assertEquals(Map.of("X-Tenant", List.of("acme")), find.staticHeaders());
        assertEquals(Map.of("X-Auth", new DynamicHeaderDescriptor("computeAuth", false)),
                find.dynamicHeaders());

        var beanDescriptor = assertInstanceOf(ClientParamDescriptor.Bean.class, find.params().get(0));
        assertEquals("t.Filter", beanDescriptor.beanType().getName());
        assertEquals(List.of(
                        new ClientParamDescriptor.BeanField("q",
                                ClientParamDescriptor.BeanField.Kind.QUERY, "q", "*"),
                        new ClientParamDescriptor.BeanField("base",
                                ClientParamDescriptor.BeanField.Kind.HEADER, "X-Base", null)),
                beanDescriptor.fields(), "subclass fields first, hierarchy walk order");
    }

    @Test
    void primitivesVoidAndVarargs_compileAndDispatch() throws Exception {
        File api = writeSource("t/Mixed.java", """
                package t;
                import jakarta.ws.rs.DELETE;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.POST;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.QueryParam;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

                @RegisterRestClient
                @Path("/mixed")
                public interface Mixed {

                    @GET
                    boolean flag(@QueryParam("v") int value);

                    @DELETE
                    void remove(@QueryParam("id") long id);

                    @POST
                    int batch(String... items);
                }
                """);
        var compilation = compile(api);
        ClientProxyFactory factory = factoryOf(compilation, "t.Mixed");
        var invoker = new StubInvoker();
        Object proxy = factory.newProxy(invoker);

        invoker.canned = Boolean.TRUE;
        Object flag = proxy.getClass().getMethod("flag", int.class).invoke(proxy, 7);
        assertEquals(Boolean.TRUE, flag);
        assertArrayEquals(new Object[] { 7 }, invoker.lastArgs, "primitive boxed into args array");

        invoker.canned = null;
        proxy.getClass().getMethod("remove", long.class).invoke(proxy, 5L);
        assertEquals(1, invoker.lastIndex, "void method dispatches too");

        invoker.canned = 3;
        Object batch = proxy.getClass().getMethod("batch", String[].class)
                .invoke(proxy, (Object) new String[] { "a", "b" });
        assertEquals(3, batch);
        assertEquals(2, invoker.lastIndex);
        // varargs arrive as a single array argument
        assertArrayEquals(new String[] { "a", "b" }, (String[]) invoker.lastArgs[0]);

        ClientMethodDescriptor batchDescriptor = factory.descriptor().methods().get(2);
        assertEquals(List.of(String[].class), batchDescriptor.parameterTypes());
    }

    @Test
    void subResourceLocator_generatesTransitively() throws Exception {
        File sub = writeSource("t/ItemApi.java", """
                package t;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/items")
                public interface ItemApi {
                    @GET
                    String list();
                }
                """);
        File api = writeSource("t/RootApi.java", """
                package t;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

                @RegisterRestClient
                @Path("/root")
                public interface RootApi {

                    @GET
                    String ping();

                    @Path("/{id}/items")
                    ItemApi items(@PathParam("id") String id);
                }
                """);
        var compilation = compile(sub, api);

        ClientDescriptor root = factoryOf(compilation, "t.RootApi").descriptor();
        ClientMethodDescriptor locator = root.methods().get(1);
        assertNull(locator.httpMethod(), "locator entry keeps a null verb");
        assertEquals("/root/{id}/items", locator.pathTemplate());

        // The sub-resource interface (not @RegisterRestClient itself) got its own client.
        ClientDescriptor item = factoryOf(compilation, "t.ItemApi").descriptor();
        assertEquals("/items", item.methods().get(0).pathTemplate());

        Path services = compilation.outputDir().toPath()
                .resolve("META-INF/services/io.vidocq.cyrano.spi.gen.ClientProxyFactory");
        String content = Files.readString(services);
        assertTrue(content.contains("t.RootApi$$CyranoClient$Factory"));
        assertTrue(content.contains("t.ItemApi$$CyranoClient$Factory"));
    }

    // ------------------------------------------------------------------
    // Safety-valve cases (no generated class, compilation still succeeds)
    // ------------------------------------------------------------------

    private void assertSkipped(ProcessorTestHarness.Compilation compilation, String ifaceFqn) {
        assertThrows(ClassNotFoundException.class,
                () -> compilation.loader().loadClass(ifaceFqn + "$$CyranoClient"));
    }

    @Test
    void valve_genericInterface() throws Exception {
        var compilation = compile(writeSource("t/Gen.java", """
                package t;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

                @RegisterRestClient
                @Path("/gen")
                public interface Gen<T> {
                    @GET
                    String get();
                }
                """));
        assertSkipped(compilation, "t.Gen");
    }

    @Test
    void valve_pathPlaceholderMismatch() throws Exception {
        var compilation = compile(writeSource("t/BadPath.java", """
                package t;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

                @RegisterRestClient
                @Path("/bad")
                public interface BadPath {
                    @GET
                    @Path("/{id}")
                    String get();
                }
                """));
        assertSkipped(compilation, "t.BadPath");
    }

    @Test
    void valve_duplicateClientHeaderParam() throws Exception {
        var compilation = compile(writeSource("t/DupHeader.java", """
                package t;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

                @RegisterRestClient
                @Path("/dup")
                @ClientHeaderParam(name = "X-Dup", value = "a")
                @ClientHeaderParam(name = "X-Dup", value = "b")
                public interface DupHeader {
                    @GET
                    String get();
                }
                """));
        assertSkipped(compilation, "t.DupHeader");
    }

    @Test
    void valve_missingComputeMethod() throws Exception {
        var compilation = compile(writeSource("t/BadCompute.java", """
                package t;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

                @RegisterRestClient
                @Path("/badcompute")
                @ClientHeaderParam(name = "X-Auth", value = "{nope}")
                public interface BadCompute {
                    @GET
                    String get();
                }
                """));
        assertSkipped(compilation, "t.BadCompute");
    }

    @Test
    void valve_verbOnCloseMethod() throws Exception {
        var compilation = compile(writeSource("t/Closing.java", """
                package t;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

                @RegisterRestClient
                @Path("/closing")
                public interface Closing {
                    @GET
                    String close();
                }
                """));
        assertSkipped(compilation, "t.Closing");
    }

    @Test
    void closeableInterface_isStillGenerated() throws Exception {
        var compilation = compile(writeSource("t/WithClose.java", """
                package t;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

                @RegisterRestClient
                @Path("/wc")
                public interface WithClose extends java.io.Closeable {
                    @GET
                    String get();
                }
                """));
        ClientProxyFactory factory = factoryOf(compilation, "t.WithClose");
        assertEquals(1, factory.descriptor().methods().size(),
                "close() inherited from Closeable is covered by the synthetic close()");
        var invoker = new StubInvoker();
        Object proxy = factory.newProxy(invoker);
        ((java.io.Closeable) proxy).close();
    }

    /**
     * BUG-20261028-01: two distinct {@code @ClientHeaderParam} on one element are valid (MP Rest
     * Client 4.0 §7.2 only forbids the same name twice on the same target); the processor counted
     * the repeated annotations twice and skipped the interface.
     */
    @Test
    void twoDistinctClientHeaderParams_onOneElement_areGenerated() throws Exception {
        var compilation = compile(writeSource("t/TwoHeaders.java", """
                package t;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

                @RegisterRestClient
                @Path("/two")
                @ClientHeaderParam(name = "X-A", value = "a")
                @ClientHeaderParam(name = "X-B", value = "b")
                public interface TwoHeaders {
                    @GET
                    String get();
                }
                """));
        ClientProxyFactory factory = factoryOf(compilation, "t.TwoHeaders");
        assertEquals(Map.of("X-A", List.of("a"), "X-B", List.of("b")),
                factory.descriptor().methods().getFirst().staticHeaders());
    }

    // ------------------------------------------------------------------
    // Generated access: compute methods and bean fields without reflection
    // ------------------------------------------------------------------

    @Test
    void computeMethodsAndBeanFieldsAreReadByGeneratedCode() throws Exception {
        File helper = writeSource("t/Tokens.java", """
                package t;

                public final class Tokens {
                    private Tokens() {
                    }

                    public static String token(String headerName) throws java.io.IOException {
                        return "for " + headerName;
                    }
                }
                """);
        File bean = writeSource("t/Query.java", """
                package t;
                import jakarta.ws.rs.HeaderParam;
                import jakarta.ws.rs.QueryParam;

                public class Query {
                    @QueryParam("open") String open = "o";
                    @QueryParam("count") public int count = 3;
                    @HeaderParam("X-Hidden") private String hidden = "h";
                }
                """);
        File api = writeSource("t/Generated.java", """
                package t;
                import jakarta.ws.rs.BeanParam;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

                @RegisterRestClient
                @Path("generated")
                public interface Generated {
                    @GET
                    @ClientHeaderParam(name = "X-Default", value = "{local}")
                    @ClientHeaderParam(name = "X-Static", value = "{shared}")
                    @ClientHeaderParam(name = "X-External", value = "{t.Tokens.token}")
                    @ClientHeaderParam(name = "X-Private", value = "{hidden}")
                    String get(@BeanParam Query query);

                    default String local() {
                        return "local";
                    }

                    static String[] shared(String headerName) {
                        return new String[] {headerName, "shared"};
                    }

                    private String hidden() {
                        return "hidden";
                    }
                }
                """);
        var compilation = compile(helper, bean, api);
        ClientProxyFactory factory = factoryOf(compilation, "t.Generated");
        ClientMethodDescriptor get = factory.descriptor().methods().get(0);
        Object proxy = factory.newProxy(new StubInvoker());

        var headers = get.dynamicHeaders();
        assertEquals("local", headers.get("X-Default").compute().compute(proxy, "X-Default"));
        assertArrayEquals(new String[] {"X-Static", "shared"},
                (String[]) headers.get("X-Static").compute().compute(proxy, "X-Static"));
        assertEquals("for X-External", headers.get("X-External").compute().compute(proxy, "X-External"));
        assertNull(headers.get("X-Private").compute(), "a private compute method stays on the reflective path");

        var beanDescriptor = assertInstanceOf(ClientParamDescriptor.Bean.class, get.params().get(0));
        Object query = compilation.loader().loadClass("t.Query").getConstructor().newInstance();
        var fields = beanDescriptor.fields();
        assertEquals("o", fields.get(0).accessor().apply(query));
        assertEquals(3, fields.get(1).accessor().apply(query));
        assertNull(fields.get(2).accessor(), "a private field stays on the reflective path");
    }
}
