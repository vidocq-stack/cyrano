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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParams;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CyranoInterfaceScanner} TDD tests — covers spec MicroProfile Rest Client
 * 4.0 §3 (customer interface) and §3.1 (base parameter types).
 */
class CyranoInterfaceScannerTest {

    @Path("/users")
    interface UserService {
        @GET
        @Path("/{id}")
        String getUser(@PathParam("id") long id);

        @GET
        String search(@QueryParam("q") String query);

        @POST
        String create();
    }

    interface NotAnnotated {
        String doNothing();
    }

    @Path("/")
    interface MultipleHttpVerb {
        @jakarta.ws.rs.DELETE
        @POST
        String call();
    }

    @Path("/{id}")
    interface MissingPathParamClient {
        @GET
        String call();
    }

    @Path("/root")
    interface PathParamWithoutTemplateClient {
        @GET
        String call(@PathParam("id") String id);
    }

    @Path("/root/{id}")
    interface MismatchedPathParamClient {
        @GET
        String call(@PathParam("other") String id);
    }

    @ClientHeaderParam(name = "X-Test", value = "{missingMethod}")
    interface MissingComputeMethodClient {
        @GET String call();
    }

    @ClientHeaderParam(name = "X-Test", value = "{invalid}")
    interface InvalidComputeSignatureClient {
        @GET String call();
        default String invalid(Integer v) { return String.valueOf(v); }
    }

    @ClientHeaderParams({
            @ClientHeaderParam(name = "X-Test", value = "first"),
            @ClientHeaderParam(name = "X-Test", value = "second")
    })
    interface DuplicateClientHeaderOnType {
        @GET String call();
    }

    interface MultiValueWithComputeClient {
        @GET
        @ClientHeaderParam(name = "X-Test", value = {"{compute}", "literal"})
        String call();
        default String compute(String h) { return h; }
    }

    @Test
    void scanner_extracts_request_specs_spec_section3() throws Exception {
        //§3: Each method annotated by an HTTP verb becomes a RequestSpec
        var specs = CyranoInterfaceScanner.scan(UserService.class);

        Method getUser = UserService.class.getMethod("getUser", long.class);
        RequestSpec spec = specs.get(getUser);

        assertNotNull(spec, "getUser must be scanned");
        assertEquals("GET", spec.httpMethod());
        assertEquals("/users/{id}", spec.pathTemplate());
        assertEquals(String.class, spec.returnType());
    }

    @Test
    void scanner_extracts_path_param_binding_spec_section3_1() throws Exception {
        //§3.1: @PathParam → ParamBinding.Path
        var specs = CyranoInterfaceScanner.scan(UserService.class);
        Method getUser = UserService.class.getMethod("getUser", long.class);
        RequestSpec spec = specs.get(getUser);

        assertEquals(1, spec.bindings().size());
        ParamBinding b = spec.bindings().get(0);
        var path = assertInstanceOf(ParamBinding.Path.class, b);
        assertEquals(0, path.paramIndex());
        assertEquals("id", path.name());
    }

    @Test
    void scanner_extracts_query_param_binding_spec_section3_1() throws Exception {
        //§3.1: @QueryParam → ParamBinding.Query
        var specs = CyranoInterfaceScanner.scan(UserService.class);
        Method search = UserService.class.getMethod("search", String.class);
        RequestSpec spec = specs.get(search);

        assertEquals("GET", spec.httpMethod());
        assertEquals("/users", spec.pathTemplate());
        assertEquals(1, spec.bindings().size());
        var q = assertInstanceOf(ParamBinding.Query.class, spec.bindings().get(0));
        assertEquals("q", q.name());
    }

    @Test
    void scanner_supports_post_without_subpath() throws Exception {
        var specs = CyranoInterfaceScanner.scan(UserService.class);
        Method create = UserService.class.getMethod("create");
        RequestSpec spec = specs.get(create);
        assertEquals("POST", spec.httpMethod());
        assertEquals("/users", spec.pathTemplate());
        assertTrue(spec.bindings().isEmpty());
    }

    @Test
    void scanner_rejects_interface_without_http_method_annotations() {
        //§3: a client interface must have at least one annotated method
        assertThrows(IllegalArgumentException.class,
                () -> CyranoInterfaceScanner.scan(NotAnnotated.class));
    }

    @Test
    void scanner_returns_one_entry_per_method() {
        Map<Method, RequestSpec> specs = CyranoInterfaceScanner.scan(UserService.class);
        assertEquals(3, specs.size());
    }

    @Test
    void scanner_rejects_multiple_http_method_annotations() {
        assertThrows(IllegalArgumentException.class,
                () -> CyranoInterfaceScanner.scan(MultipleHttpVerb.class));
    }

    @Test
    void scanner_rejects_missing_path_param_for_template() {
        assertThrows(IllegalArgumentException.class,
                () -> CyranoInterfaceScanner.scan(MissingPathParamClient.class));
    }

    @Test
    void scanner_rejects_path_param_without_template() {
        assertThrows(IllegalArgumentException.class,
                () -> CyranoInterfaceScanner.scan(PathParamWithoutTemplateClient.class));
    }

    @Test
    void scanner_rejects_mismatched_path_param_name() {
        assertThrows(IllegalArgumentException.class,
                () -> CyranoInterfaceScanner.scan(MismatchedPathParamClient.class));
    }

    @Test
    void scanner_rejects_missing_header_compute_method() {
        assertThrows(IllegalArgumentException.class,
                () -> CyranoInterfaceScanner.scan(MissingComputeMethodClient.class));
    }

    @Test
    void scanner_rejects_invalid_header_compute_signature() {
        assertThrows(IllegalArgumentException.class,
                () -> CyranoInterfaceScanner.scan(InvalidComputeSignatureClient.class));
    }

    @Test
    void scanner_rejects_duplicate_client_header_name_on_same_target() {
        assertThrows(IllegalArgumentException.class,
                () -> CyranoInterfaceScanner.scan(DuplicateClientHeaderOnType.class));
    }

    @Test
    void scanner_rejects_mixed_compute_and_literal_header_values() {
        assertThrows(IllegalArgumentException.class,
                () -> CyranoInterfaceScanner.scan(MultiValueWithComputeClient.class));
    }
}

