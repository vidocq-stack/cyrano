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

import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParams;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CyranoInterfaceScanner}: client interface validation (MP Rest Client 4.0 §2 — an invalid
 * interface is a definition error) and {@code @ClientHeaderParam} rules (§6.5).
 */
class CyranoInterfaceScannerValidationTest {

    private static final String X_H = "X-H";

    public static final class Helpers {
        private Helpers() {
            // static compute methods only
        }

        public static String good() {
            return "g";
        }

        public static int wrongType() {
            return 0;
        }

        public String notStatic() {
            return "n";
        }
    }

    public interface NotAnHttpClient {
        String nothing();
    }

    @ClientHeaderParams({
            @ClientHeaderParam(name = X_H, value = "a"),
            @ClientHeaderParam(name = X_H, value = "b")})
    public interface DuplicateTypeHeaders {
        @GET
        String get();
    }

    public interface DuplicateMethodHeaders {
        @GET
        @ClientHeaderParams({
                @ClientHeaderParam(name = X_H, value = "a"),
                @ClientHeaderParam(name = X_H, value = "b")})
        String get();
    }

    public interface MixedComputeValue {
        @GET
        @ClientHeaderParam(name = X_H, value = {"{compute}", "static"})
        String get();

        default String compute() {
            return "c";
        }
    }

    public interface ExternalCompute {
        @GET
        @ClientHeaderParam(name = X_H,
                value = "{io.vidocq.cyrano.internal.CyranoInterfaceScannerValidationTest$Helpers.good}")
        String get();
    }

    public interface ExternalWrongType {
        @GET
        @ClientHeaderParam(name = X_H,
                value = "{io.vidocq.cyrano.internal.CyranoInterfaceScannerValidationTest$Helpers.wrongType}")
        String get();
    }

    public interface ExternalNotStatic {
        @GET
        @ClientHeaderParam(name = X_H,
                value = "{io.vidocq.cyrano.internal.CyranoInterfaceScannerValidationTest$Helpers.notStatic}")
        String get();
    }

    public interface ExternalMissingClass {
        @GET
        @ClientHeaderParam(name = X_H, value = "{com.example.Missing.compute}")
        String get();
    }

    public interface ComputeWithTwoParameters {
        @GET
        @ClientHeaderParam(name = X_H, value = "{compute}")
        String get();

        default String compute(String a, String b) {
            return a + b;
        }
    }

    /** A later method-level declaration replaces the interface-level one (§6.5). */
    @ClientHeaderParam(name = X_H, value = "type")
    @ClientHeaderParam(name = "X-Dyn", value = "{compute}")
    @Consumes("text/plain")
    @Produces("text/plain")
    public interface Overrides {
        @GET
        @ClientHeaderParam(name = X_H, value = "{compute}")
        @ClientHeaderParam(name = "X-Dyn", value = "static")
        @Produces("application/xml")
        String get();

        default String compute() {
            return "c";
        }
    }

    public static class BaseBean {
        @PathParam("id")
        String id;
    }

    /** Every field binding kind, including an inherited and a shadowing field. */
    public static class Bean extends BaseBean {
        static final String IGNORED_STATIC = "ignored";
        @CookieParam("c")
        String cookie;
        @FormParam("f")
        String form;
        @MatrixParam("m")
        String matrix;
        @HeaderParam("h")
        String header;
        String notBound;
    }

    @Path("/b")
    public interface BeanClient {
        @POST
        @Path("/{id}")
        String post(@BeanParam Bean bean);

        @POST
        @Path("/{other}")
        String unmatched(@BeanParam Bean bean);
    }

    @Path("/b")
    public interface BeanClientValid {
        @POST
        @Path("/{id}")
        String post(@BeanParam Bean bean);
    }

    private static IllegalArgumentException definitionError(Class<?> iface) {
        return assertThrows(IllegalArgumentException.class, () -> CyranoInterfaceScanner.scan(iface));
    }

    private static RequestSpec only(Class<?> iface) {
        Map<Method, RequestSpec> specs = CyranoInterfaceScanner.scan(iface);
        assertEquals(1, specs.size());
        return specs.values().iterator().next();
    }

    @Test
    void a_class_is_not_a_client_interface() {
        IllegalArgumentException e = definitionError(String.class);
        assertTrue(e.getMessage().contains("interfaces"), e.getMessage());
    }

    @Test
    void an_interface_without_http_method_is_rejected() {
        IllegalArgumentException e = definitionError(NotAnHttpClient.class);
        assertTrue(e.getMessage().contains("HTTP verb"), e.getMessage());
    }

    @Test
    void a_header_declared_twice_is_rejected_on_the_type_and_on_the_method_spec_section6_5() {
        assertTrue(definitionError(DuplicateTypeHeaders.class).getMessage().contains("multiple times"));
        assertTrue(definitionError(DuplicateMethodHeaders.class).getMessage().contains("#get"));
    }

    @Test
    void a_compute_method_cannot_be_mixed_with_static_values_spec_section6_5() {
        assertTrue(definitionError(MixedComputeValue.class).getMessage().contains("cannot mix"));
    }

    @Test
    void external_compute_methods_must_be_static_return_strings_and_exist_spec_section6_5() {
        assertEquals(1, CyranoInterfaceScanner.scan(ExternalCompute.class).size());
        for (Class<?> invalid : List.of(ExternalWrongType.class, ExternalNotStatic.class,
                ExternalMissingClass.class, ComputeWithTwoParameters.class)) {
            assertTrue(definitionError(invalid).getMessage().contains("Invalid or missing compute method"),
                    invalid.getName());
        }
    }

    @Test
    void method_level_header_and_media_types_replace_the_interface_level_ones() {
        RequestSpec spec = only(Overrides.class);

        assertEquals("compute", spec.dynamicHeaders().get(X_H).methodName());
        assertEquals(List.of("static"), spec.staticHeaders().get("X-Dyn"));
        assertTrue(spec.dynamicHeaders().get(X_H).required());
        assertEquals(List.of("application/xml"), spec.produces());
        assertEquals(List.of("text/plain"), spec.consumes());
        assertEquals("/", spec.pathTemplate());
    }

    @Test
    void bean_param_fields_of_every_kind_are_bound_and_path_fields_are_validated_spec_section3_1() {
        RequestSpec spec = only(BeanClientValid.class);
        ParamBinding.Bean bean = assertInstanceOf(ParamBinding.Bean.class, spec.bindings().get(0));

        assertEquals(List.of("c", "f", "m", "h", "id"),
                bean.fields().stream().map(ParamBinding.FieldBinding::name).toList());

        IllegalArgumentException e = definitionError(BeanClient.class);
        assertTrue(e.getMessage().contains("id"), e.getMessage());
    }
}
