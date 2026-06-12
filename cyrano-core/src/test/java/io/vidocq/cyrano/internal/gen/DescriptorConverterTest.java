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
package io.vidocq.cyrano.internal.gen;

import io.vidocq.cyrano.internal.CyranoInterfaceScanner;
import io.vidocq.cyrano.internal.RequestSpec;
import io.vidocq.cyrano.spi.gen.ClientDescriptor;
import io.vidocq.cyrano.spi.gen.ClientMethodDescriptor;
import io.vidocq.cyrano.spi.gen.ClientParamDescriptor;
import io.vidocq.cyrano.spi.gen.DynamicHeaderDescriptor;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden equivalence proof for the APT-first chain (codegen audit CG-01):
 * a {@link ClientDescriptor} written exactly as the annotation processor emits it,
 * converted by {@link DescriptorConverter}, must reproduce — field by field — the
 * {@link RequestSpec}s built by the reflective {@link CyranoInterfaceScanner}.
 *
 * <p>Any divergence here means the generated path would behave differently from
 * the runtime-fallback path, which is the one bug class this chantier must never
 * introduce.</p>
 */
class DescriptorConverterTest {

    // --- fixtures ---------------------------------------------------------

    public static class PagingBean {
        @QueryParam("page")
        @DefaultValue("0")
        public int page;
        @HeaderParam("X-Trace")
        public String trace;
    }

    public static class SortedPagingBean extends PagingBean {
        @QueryParam("sort")
        public String sort;
    }

    @Path("/sub")
    public interface SubApi {
        @GET
        String read();
    }

    @Path("/orders")
    @Consumes("application/json")
    @Produces("application/json")
    @ClientHeaderParam(name = "X-Tenant", value = "acme")
    public interface OrderApi {

        @GET
        @Path("/{id}")
        String byId(@PathParam("id") String id,
                    @QueryParam("verbose") @DefaultValue("false") String verbose,
                    @HeaderParam("X-Req") String req,
                    @CookieParam("session") String session,
                    @MatrixParam("m") String m);

        @POST
        @Consumes("text/plain")
        @Produces("text/plain")
        @ClientHeaderParam(name = "X-Auth", value = "{computeAuth}", required = false)
        String create(String body);

        @POST
        @Path("/form")
        String form(@FormParam("a") String a, @FormParam("b") @DefaultValue("x") String b);

        @GET
        @Path("/search")
        CompletionStage<String> search(@BeanParam SortedPagingBean paging);

        @Path("/{id}/items")
        SubApi items(@PathParam("id") String id);

        @DELETE
        @Path("/{id}")
        void delete(@PathParam("id") String id);

        default String computeAuth() {
            return "token";
        }
    }

    /** The descriptor exactly as CyranoClientProcessor will emit it for {@link OrderApi}. */
    private static ClientDescriptor orderApiDescriptor() {
        return new ClientDescriptor(OrderApi.class, List.of(
                new ClientMethodDescriptor(
                        "byId",
                        List.of(String.class, String.class, String.class, String.class, String.class),
                        "GET", "/orders/{id}",
                        List.of(new ClientParamDescriptor.Path(0, "id", null),
                                new ClientParamDescriptor.Query(1, "verbose", "false"),
                                new ClientParamDescriptor.Header(2, "X-Req", null),
                                new ClientParamDescriptor.Cookie(3, "session", null),
                                new ClientParamDescriptor.Matrix(4, "m", null)),
                        List.of("application/json"), List.of("application/json"),
                        Map.of("X-Tenant", List.of("acme")), Map.of()),
                new ClientMethodDescriptor(
                        "create",
                        List.of(String.class),
                        "POST", "/orders",
                        List.of(new ClientParamDescriptor.Body(0)),
                        List.of("text/plain"), List.of("text/plain"),
                        Map.of("X-Tenant", List.of("acme")),
                        Map.of("X-Auth", new DynamicHeaderDescriptor("computeAuth", false))),
                new ClientMethodDescriptor(
                        "form",
                        List.of(String.class, String.class),
                        "POST", "/orders/form",
                        List.of(new ClientParamDescriptor.Form(0, "a", null),
                                new ClientParamDescriptor.Form(1, "b", "x")),
                        List.of("application/json"), List.of("application/json"),
                        Map.of("X-Tenant", List.of("acme")), Map.of()),
                new ClientMethodDescriptor(
                        "search",
                        List.of(SortedPagingBean.class),
                        "GET", "/orders/search",
                        List.of(new ClientParamDescriptor.Bean(0, SortedPagingBean.class, List.of(
                                new ClientParamDescriptor.BeanField(
                                        "sort", ClientParamDescriptor.BeanField.Kind.QUERY, "sort", null),
                                new ClientParamDescriptor.BeanField(
                                        "page", ClientParamDescriptor.BeanField.Kind.QUERY, "page", "0"),
                                new ClientParamDescriptor.BeanField(
                                        "trace", ClientParamDescriptor.BeanField.Kind.HEADER, "X-Trace", null)))),
                        List.of("application/json"), List.of("application/json"),
                        Map.of("X-Tenant", List.of("acme")), Map.of()),
                new ClientMethodDescriptor(
                        "items",
                        List.of(String.class),
                        null, "/orders/{id}/items",
                        List.of(new ClientParamDescriptor.Path(0, "id", null)),
                        List.of("application/json"), List.of("application/json"),
                        Map.of("X-Tenant", List.of("acme")), Map.of()),
                new ClientMethodDescriptor(
                        "delete",
                        List.of(String.class),
                        "DELETE", "/orders/{id}",
                        List.of(new ClientParamDescriptor.Path(0, "id", null)),
                        List.of("application/json"), List.of("application/json"),
                        Map.of("X-Tenant", List.of("acme")), Map.of())));
    }

    // --- the golden proof -------------------------------------------------

    @Test
    void convertedSpecs_matchScannerOutput_fieldByField() {
        Map<Method, RequestSpec> scanned = CyranoInterfaceScanner.scan(OrderApi.class);
        List<RequestSpec> converted = DescriptorConverter.convert(orderApiDescriptor());

        assertEquals(scanned.size(), converted.size(),
                "Descriptor must cover exactly the methods the scanner finds");
        for (RequestSpec spec : converted) {
            RequestSpec reference = scanned.get(spec.method());
            assertNotNull(reference, "Scanner has no spec for " + spec.method());
            assertEquals(reference, spec, "Divergence on " + spec.method().getName());
        }
    }

    @Test
    void convertedSpecOrder_followsDescriptorOrder() {
        List<RequestSpec> converted = DescriptorConverter.convert(orderApiDescriptor());
        assertEquals(List.of("byId", "create", "form", "search", "items", "delete"),
                converted.stream().map(s -> s.method().getName()).toList(),
                "methodIndex contract: spec order == descriptor order");
    }

    @Test
    void unknownMethod_failsWithClearError() {
        var bogus = new ClientDescriptor(OrderApi.class, List.of(
                new ClientMethodDescriptor("nope", List.of(), "GET", "/x",
                        List.of(), List.of(), List.of(), Map.of(), Map.of())));
        var e = assertThrows(IllegalArgumentException.class, () -> DescriptorConverter.convert(bogus));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
    }

    @Test
    void unknownBeanField_failsWithClearError() {
        var bogus = new ClientDescriptor(OrderApi.class, List.of(
                new ClientMethodDescriptor("search", List.of(SortedPagingBean.class), "GET", "/orders/search",
                        List.of(new ClientParamDescriptor.Bean(0, SortedPagingBean.class, List.of(
                                new ClientParamDescriptor.BeanField("missing",
                                        ClientParamDescriptor.BeanField.Kind.QUERY, "q", null)))),
                        List.of(), List.of(), Map.of(), Map.of())));
        var e = assertThrows(IllegalArgumentException.class, () -> DescriptorConverter.convert(bogus));
        assertTrue(e.getMessage().contains("missing"), e.getMessage());
    }
}
