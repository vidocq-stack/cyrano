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

import io.vidocq.cyrano.spi.gen.ClientMethodDescriptor;
import io.vidocq.cyrano.spi.gen.ClientParamDescriptor;
import io.vidocq.cyrano.spi.gen.DynamicHeaderDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Client definitions of the processor beyond the main battery: every parameter kind,
 * {@code @ClientHeaderParam} compute methods of another class, header overriding, and the
 * definitions the runtime scanner rejects (MP Rest Client 4.0 §3, §3.2, §7) — which the
 * processor skips so that the fallback surfaces the spec's {@code RestClientDefinitionException}.
 * Every generated client is compiled and loaded.
 */
class CyranoClientProcessorDefinitionsTest {

    private static final String IMPORTS = """
            import jakarta.ws.rs.*;
            import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
            import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
            """;

    @TempDir
    Path tempDir;

    private File source(String relativePath, String content) throws Exception {
        return ProcessorTestHarness.writeSource(tempDir, relativePath, content);
    }

    /** A client of package {@code t}, with the common imports. */
    private File client(String simpleName, String body) throws Exception {
        return source("t/" + simpleName + ".java", "package t;\n" + IMPORTS + body);
    }

    private ProcessorTestHarness.Compilation compile(File... sources) throws Exception {
        return ProcessorTestHarness.compileWithProcessor(tempDir, sources);
    }

    private static void assertSkipped(ProcessorTestHarness.Compilation compilation, String ifaceFqn) {
        ClassLoader loader = compilation.loader();
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass(ifaceFqn + "$$CyranoClient"));
    }

    private static ClientMethodDescriptor method(ProcessorTestHarness.Compilation compilation, String ifaceFqn,
                                                 String methodName) throws Exception {
        return compilation.factoryOf(ifaceFqn).descriptor().methods().stream()
                .filter(m -> m.methodName().equals(methodName))
                .findFirst().orElseThrow();
    }

    @Test
    void every_parameter_kind_is_described() throws Exception {
        var compilation = compile(client("Params", """
                @RegisterRestClient
                @Path("/items")
                public interface Params {
                    @POST
                    @Path("/{id}")
                    String send(@PathParam("id") String id, @CookieParam("c") String cookie,
                                @FormParam("f") @DefaultValue("none") String form,
                                @MatrixParam("m") String matrix, @HeaderParam("h") String header);
                }
                """));

        ClientMethodDescriptor send = method(compilation, "t.Params", "send");

        assertEquals(List.of(
                new ClientParamDescriptor.Path(0, "id", null),
                new ClientParamDescriptor.Cookie(1, "c", null),
                new ClientParamDescriptor.Form(2, "f", "none"),
                new ClientParamDescriptor.Matrix(3, "m", null),
                new ClientParamDescriptor.Header(4, "h", null)), send.params());
        assertEquals("/items/{id}", send.pathTemplate());
    }

    @Test
    void bean_param_fields_of_every_kind_are_described_and_bind_path_placeholders() throws Exception {
        File bean = source("t/Ids.java", """
                package t;
                import jakarta.ws.rs.*;

                public class Ids {
                    public static String IGNORED;
                    @PathParam("id") public String id;
                    @CookieParam("c") public String cookie;
                    @FormParam("f") public String form;
                    @MatrixParam("m") public String matrix;
                    public String unbound;
                }
                """);
        var compilation = compile(bean, client("BeanApi", """
                @RegisterRestClient
                @Path("/beans")
                public interface BeanApi {
                    @POST
                    @Path("/{id}")
                    String send(@BeanParam Ids ids);
                }
                """));

        ClientParamDescriptor.Bean param = (ClientParamDescriptor.Bean) method(compilation, "t.BeanApi", "send")
                .params().getFirst();

        assertEquals(List.of(ClientParamDescriptor.BeanField.Kind.PATH, ClientParamDescriptor.BeanField.Kind.COOKIE,
                        ClientParamDescriptor.BeanField.Kind.FORM, ClientParamDescriptor.BeanField.Kind.MATRIX),
                param.fields().stream().map(ClientParamDescriptor.BeanField::kind).toList());
    }

    @Test
    void compute_methods_of_another_class_and_array_returns_are_dynamic_headers_spec_section7() throws Exception {
        File util = source("t/Headers.java", """
                package t;

                public final class Headers {
                    private Headers() {
                    }

                    public static String token(String name) {
                        return name;
                    }

                    public String notStatic() {
                        return "";
                    }
                }
                """);
        var compilation = compile(util, client("Computed", """
                @RegisterRestClient
                @Path("/computed")
                @ClientHeaderParam(name = "X-Token", value = "{t.Headers.token}", required = false)
                public interface Computed {
                    @GET
                    @ClientHeaderParam(name = "X-List", value = "{list}")
                    String get();

                    default String[] list() {
                        return new String[] {"a", "b"};
                    }
                }
                """));

        ClientMethodDescriptor get = method(compilation, "t.Computed", "get");

        assertEquals(Map.of(
                "X-Token", new DynamicHeaderDescriptor("t.Headers.token", false),
                "X-List", new DynamicHeaderDescriptor("list", true)), get.dynamicHeaders());
    }

    @Test
    void a_method_header_overrides_the_interface_header_of_the_same_name() throws Exception {
        var compilation = compile(client("Overrides", """
                @RegisterRestClient
                @Path("/overrides")
                @ClientHeaderParam(name = "X-Dynamic", value = "{value}")
                @ClientHeaderParam(name = "X-Static", value = "type")
                public interface Overrides {
                    @GET
                    @ClientHeaderParam(name = "X-Dynamic", value = "fixed")
                    @ClientHeaderParam(name = "X-Static", value = "{value}")
                    String get();

                    default String value() {
                        return "computed";
                    }
                }
                """));

        ClientMethodDescriptor get = method(compilation, "t.Overrides", "get");

        assertEquals(Map.of("X-Dynamic", List.of("fixed")), get.staticHeaders());
        assertEquals(Map.of("X-Static", new DynamicHeaderDescriptor("value", true)), get.dynamicHeaders());
    }

    @Test
    void a_client_of_the_unnamed_package_and_a_nested_client_are_generated() throws Exception {
        File unnamed = source("RootApi.java", IMPORTS + """
                @RegisterRestClient
                @Path("/root")
                public interface RootApi {
                    @GET
                    String get();
                }
                """);
        var compilation = compile(unnamed, client("Outer", """
                public final class Outer {
                    private Outer() {
                    }

                    @RegisterRestClient
                    @Path("/nested")
                    public interface Inner {
                        @GET
                        String get();
                    }
                }
                """));

        assertEquals("/root", method(compilation, "RootApi", "get").pathTemplate());
        assertEquals("/nested", method(compilation, "t.Outer$Inner", "get").pathTemplate());
    }

    @Test
    void definitions_rejected_by_the_runtime_are_skipped() throws Exception {
        File util = source("t/Util.java", """
                package t;

                public final class Util {
                    private Util() {
                    }

                    public String instance() {
                        return "";
                    }

                    public static int number() {
                        return 1;
                    }
                }
                """);
        var compilation = compile(util,
                client("GenericMethod", """
                        @RegisterRestClient
                        public interface GenericMethod {
                            @GET
                            <T> T get();
                        }
                        """),
                client("TwoVerbs", """
                        @RegisterRestClient
                        public interface TwoVerbs {
                            @GET
                            @POST
                            String get();
                        }
                        """),
                client("PrimitiveBean", """
                        @RegisterRestClient
                        public interface PrimitiveBean {
                            @GET
                            String get(@BeanParam int bean);
                        }
                        """),
                client("UnboundPathParam", """
                        @RegisterRestClient
                        public interface UnboundPathParam {
                            @GET
                            String get(@PathParam("id") String id);
                        }
                        """),
                client("NoVerb", """
                        @RegisterRestClient
                        public interface NoVerb {
                            default String get() {
                                return "";
                            }
                        }
                        """),
                client("MixedCompute", """
                        @RegisterRestClient
                        @ClientHeaderParam(name = "X-Mixed", value = {"{value}", "other"})
                        public interface MixedCompute {
                            @GET
                            String get();

                            default String value() {
                                return "";
                            }
                        }
                        """),
                client("MissingClass", """
                        @RegisterRestClient
                        @ClientHeaderParam(name = "X-Missing", value = "{t.Absent.value}")
                        public interface MissingClass {
                            @GET
                            String get();
                        }
                        """),
                client("InstanceCompute", """
                        @RegisterRestClient
                        @ClientHeaderParam(name = "X-Instance", value = "{t.Util.instance}")
                        public interface InstanceCompute {
                            @GET
                            String get();
                        }
                        """),
                client("WrongReturn", """
                        @RegisterRestClient
                        @ClientHeaderParam(name = "X-Number", value = "{t.Util.number}")
                        public interface WrongReturn {
                            @GET
                            String get();
                        }
                        """),
                client("WrongParameters", """
                        @RegisterRestClient
                        @ClientHeaderParam(name = "X-Params", value = "{value}")
                        public interface WrongParameters {
                            @GET
                            String get();

                            default String value(int a, int b) {
                                return "";
                            }
                        }
                        """));

        for (String skipped : List.of("GenericMethod", "TwoVerbs", "PrimitiveBean", "UnboundPathParam", "NoVerb",
                "MixedCompute", "MissingClass", "InstanceCompute", "WrongReturn", "WrongParameters")) {
            assertSkipped(compilation, "t." + skipped);
        }
    }
}
