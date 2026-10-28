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
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.WriterInterceptor;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;
import org.eclipse.microprofile.rest.client.ext.QueryParamStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request side of {@link CyranoInvocationHandler}: parameter binding (MP Rest Client 4.0 §3.1,
 * §3.4 query styles), headers (§3.2 builder headers, §6.5 {@code @ClientHeaderParam},
 * {@code ClientHeadersFactory}), entity writing (§4.2 providers) and the client life cycle (§8.1).
 */
class CyranoInvocationHandlerRequestTest {

    private static final String BASE = "/inv";
    private static final String VALUE = "value";
    private static final String X_STATIC = "X-Static";
    private static final String X_BUILDER = "X-Builder";
    private static final String COOKIE = "Cookie";
    private static final String BOOM = "boom";

    private final RecordingHttpServer server = new RecordingHttpServer();

    @BeforeAll
    static void installRuntimeDelegate() {
        TestRuntimeDelegate.install();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** All the binding kinds of a {@code @BeanParam} (spec §3.1). */
    public static final class AllKindsBean {
        @PathParam("id")
        String id;
        @QueryParam("q")
        String q;
        @HeaderParam("X-Bean")
        String header;
        @CookieParam("bc")
        String cookie;
        @FormParam("bf")
        String form;
        @MatrixParam("bm")
        String matrix;
        @QueryParam("absent")
        String absent;
        @QueryParam("withDefault")
        @DefaultValue("dflt")
        String withDefault;
    }

    @Path(BASE)
    public interface ParamApi {
        @GET
        @Path("/q")
        String queryList(@QueryParam("v") List<String> values);

        @GET
        @Path("/q")
        String queryArray(@QueryParam("v") String[] values);

        @GET
        @Path("/q")
        String queryDefault(@QueryParam("v") @DefaultValue("dflt") String v);

        @GET
        @Path("/m")
        String matrix(@MatrixParam("a") String a, @MatrixParam("b") String b);

        @GET
        @Path("/p/{id}")
        String path(@PathParam("id") String id);

        @GET
        @Path("/c")
        String cookies(@CookieParam("c1") String c1, @CookieParam("c2") String c2);

        @POST
        @Path("/f")
        String form(@FormParam("a") String a, @FormParam("b") String b);

        @POST
        @Path("/b/{id}")
        String bean(@BeanParam AllKindsBean bean);

        @Path("/sub/{id}")
        SubApi sub(@PathParam("id") String id);
    }

    public interface SubApi {
        @GET
        @Path("/leaf")
        String leaf();
    }

    /** Static, computed (default, named, array, null, external) and optional headers (spec §6.5). */
    @Path(BASE)
    @ClientHeaderParam(name = X_STATIC, value = "static")
    public interface HeaderApi extends Closeable {
        @GET
        @Path("/h")
        @ClientHeaderParam(name = "X-Computed", value = "{computed}")
        @ClientHeaderParam(name = "X-Named", value = "{named}")
        @ClientHeaderParam(name = "X-Array", value = "{array}")
        @ClientHeaderParam(name = "X-Null", value = "{nothing}")
        @ClientHeaderParam(name = "X-External",
                value = "{io.vidocq.cyrano.internal.CyranoInvocationHandlerRequestTest.external}")
        @ClientHeaderParam(name = "X-Optional", value = "{failing}", required = false)
        String headers(@HeaderParam(X_STATIC) String override);

        @GET
        @Path("/h")
        @ClientHeaderParam(name = "X-Required", value = "{failing}")
        String requiredFailing();

        @GET
        @Path("/h")
        @ClientHeaderParam(name = "X-Checked", value = "{checked}")
        String requiredChecked();

        default String computed() {
            return "c";
        }

        default String named(String name) {
            return "n:" + name;
        }

        default String[] array() {
            return new String[]{"a1", "a2"};
        }

        default String nothing() {
            return null;
        }

        default String failing() {
            throw new IllegalStateException(BOOM);
        }

        default String checked() throws IOException {
            throw new IOException(BOOM);
        }
    }

    public static String external() {
        return "ext";
    }

    /** Adds a header and keeps the others (spec §3.3 ClientHeadersFactory). */
    public static final class AddingFactory implements ClientHeadersFactory {
        @Override
        public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incoming,
                                                     MultivaluedMap<String, String> outgoing) {
            MultivaluedMap<String, String> out = new MultivaluedHashMap<>(outgoing);
            out.putSingle("X-Factory", "f");
            return out;
        }
    }

    /** A factory that returns no headers map: the outgoing headers stay as they are. */
    public static final class NullFactory implements ClientHeadersFactory {
        @Override
        public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incoming,
                                                     MultivaluedMap<String, String> outgoing) {
            return null;
        }
    }

    @Path(BASE)
    @RegisterClientHeaders(AddingFactory.class)
    public interface FactoryApi {
        @GET
        String get();
    }

    @Path(BASE)
    @RegisterClientHeaders(NullFactory.class)
    public interface NullFactoryApi {
        @GET
        String get();
    }

    public static final class Payload {
        public String value;

        public Payload() {
            // JSON-B needs a no-arg constructor
        }

        Payload(String value) {
            this.value = value;
        }
    }

    @Path(BASE)
    public interface BodyApi {
        @POST
        @Consumes(MediaType.TEXT_PLAIN)
        String send(Payload payload);

        @POST
        String sendJson(Payload payload);

        @POST
        String sendBytes(byte[] payload);

        @POST
        String sendString(String payload);
    }

    /** Writes {@code W:<value>} (spec §4.2 MessageBodyWriter). */
    public static final class PayloadWriter implements MessageBodyWriter<Payload> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Payload.class;
        }

        @Override
        public void writeTo(Payload payload, Class<?> type, Type genericType, Annotation[] annotations,
                            MediaType mediaType, MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) throws IOException {
            entityStream.write(("W:" + payload.value + ":" + mediaType).getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class FailingWriter implements MessageBodyWriter<Payload> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return true;
        }

        @Override
        public void writeTo(Payload payload, Class<?> type, Type genericType, Annotation[] annotations,
                            MediaType mediaType, MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) throws IOException {
            throw new IOException(BOOM);
        }
    }

    private RestClientBuilder builder() {
        return RestClientBuilder.newBuilder().baseUri(server.baseUri());
    }

    private RecordingHttpServer.Exchange last() {
        return server.last();
    }

    @Test
    void collection_query_param_repeats_the_key_and_skips_null_items_spec_section3_4() {
        ParamApi client = builder().build(ParamApi.class);

        client.queryList(Arrays.asList("a", null, "b"));
        assertEquals("v=a&v=b", last().query());

        client.queryArray(new String[]{"x", null});
        assertEquals("v=x", last().query());

        client.queryList(Arrays.asList(null, null));
        assertNull(last().query());
    }

    @Test
    void query_param_default_value_applies_when_null() {
        ParamApi client = builder().build(ParamApi.class);

        client.queryDefault(null);

        assertEquals("v=dflt", last().query());
    }

    @Test
    void comma_separated_query_style_joins_the_values_spec_section3_4() {
        ParamApi client = builder().queryParamStyle(QueryParamStyle.COMMA_SEPARATED).build(ParamApi.class);

        client.queryList(List.of("a", "b"));

        assertEquals("v=a,b", last().query());
    }

    @Test
    void array_pairs_query_style_suffixes_the_key_spec_section3_4() {
        ParamApi client = builder().queryParamStyle(QueryParamStyle.ARRAY_PAIRS).build(ParamApi.class);

        client.queryList(List.of("a", "b"));

        assertEquals("v[]=a&v[]=b", last().query());
    }

    @Test
    void matrix_params_are_appended_to_the_last_segment_and_null_ones_skipped() {
        ParamApi client = builder().build(ParamApi.class);

        client.matrix("1 2", null);

        assertEquals(BASE + "/m;a=1+2", last().rawPath());
    }

    @Test
    void path_param_is_percent_encoded_except_pchars() {
        ParamApi client = builder().build(ParamApi.class);

        client.path("a b/é:@");

        assertEquals(BASE + "/p/a%20b/%C3%A9:@", last().rawPath());
    }

    @Test
    void cookie_params_are_combined_and_null_ones_skipped() {
        ParamApi client = builder().build(ParamApi.class);

        client.cookies("v1", "v2");
        assertEquals("c1=v1; c2=v2", last().header(COOKIE));

        client.cookies(null, "v2");
        assertEquals("c2=v2", last().header(COOKIE));

        client.cookies(null, null);
        assertNull(last().header(COOKIE));
    }

    @Test
    void form_params_are_url_encoded_and_null_ones_skipped() {
        ParamApi client = builder().build(ParamApi.class);

        client.form("x y", null);

        assertEquals("a=x+y", last().body());
        assertTrue(last().header("Content-Type").startsWith(MediaType.APPLICATION_FORM_URLENCODED),
                last().header("Content-Type"));
    }

    @Test
    void bean_param_fields_feed_every_binding_kind_spec_section3_1() {
        ParamApi client = builder().build(ParamApi.class);
        AllKindsBean bean = new AllKindsBean();
        bean.id = "7";
        bean.q = "query";
        bean.header = "h";
        bean.cookie = "c";
        bean.form = "f";
        bean.matrix = "m";

        client.bean(bean);

        RecordingHttpServer.Exchange ex = last();
        assertEquals(BASE + "/b/7;bm=m", ex.rawPath());
        assertEquals("q=query&withDefault=dflt", ex.query());
        assertEquals("h", ex.header("X-Bean"));
        assertEquals("bc=c", ex.header(COOKIE));
        assertEquals("bf=f", ex.body());
    }

    @Test
    void sub_resource_locator_returns_a_proxy_on_the_sub_path() {
        ParamApi client = builder().build(ParamApi.class);

        SubApi sub = client.sub("42");

        assertEquals("ok", sub.leaf());
        assertEquals(BASE + "/sub/42/leaf", last().rawPath());
    }

    @Test
    void client_header_params_are_computed_and_overridden_by_header_param_spec_section6_5() {
        HeaderApi client = builder().header(X_BUILDER, "b").header("Connection", "close").build(HeaderApi.class);

        client.headers(null);

        RecordingHttpServer.Exchange ex = last();
        assertEquals("static", ex.header(X_STATIC));
        assertEquals("c", ex.header("X-Computed"));
        assertEquals("n:X-Named", ex.header("X-Named"));
        assertEquals("a1,a2", ex.header("X-Array"));
        assertEquals("ext", ex.header("X-External"));
        assertEquals("b", ex.header(X_BUILDER));
        assertNull(ex.header("X-Null"));
        assertNull(ex.header("X-Optional"));

        client.headers("runtime");
        assertEquals("runtime", last().header(X_STATIC));
    }

    @Test
    void builder_header_is_overridden_by_header_param_spec_section3_2() {
        HeaderApi client = builder().header(X_STATIC, "builder").build(HeaderApi.class);

        client.headers(VALUE);

        assertEquals(List.of(VALUE), last().headers().get(X_STATIC));
    }

    @Test
    void a_failing_required_compute_method_fails_the_call_spec_section6_5() {
        HeaderApi client = builder().build(HeaderApi.class);

        IllegalStateException unchecked = assertThrows(IllegalStateException.class, client::requiredFailing);
        assertEquals(BOOM, unchecked.getMessage());

        IllegalStateException checked = assertThrows(IllegalStateException.class, client::requiredChecked);
        assertInstanceOf(IOException.class, checked.getCause());
    }

    @Test
    void closed_client_rejects_calls_spec_section8_1() throws IOException {
        HeaderApi client = builder().build(HeaderApi.class);

        client.close();

        assertThrows(IllegalStateException.class, () -> client.headers(VALUE));
    }

    @Test
    void client_headers_factory_updates_the_outgoing_headers() {
        builder().build(FactoryApi.class).get();
        assertEquals("f", last().header("X-Factory"));

        builder().header(X_BUILDER, "kept").build(NullFactoryApi.class).get();
        assertEquals("kept", last().header(X_BUILDER));
    }

    @Test
    void request_filter_io_failure_is_a_processing_exception() {
        FactoryApi client = builder()
                .register((ClientRequestFilter) ctx -> {
                    throw new IOException(BOOM);
                })
                .build(FactoryApi.class);

        ProcessingException e = assertThrows(ProcessingException.class, client::get);
        assertInstanceOf(IOException.class, e.getCause());
    }

    @Test
    void response_filter_io_failure_is_a_processing_exception() {
        FactoryApi client = builder()
                .register((ClientResponseFilter) (_, _) -> {
                    throw new IOException(BOOM);
                })
                .build(FactoryApi.class);

        ProcessingException e = assertThrows(ProcessingException.class, client::get);
        assertInstanceOf(IOException.class, e.getCause());
    }

    @Test
    void registered_writer_serializes_the_entity_with_the_consumed_media_type_spec_section4_2() {
        BodyApi client = builder().register(new PayloadWriter()).build(BodyApi.class);

        client.send(new Payload(VALUE));

        assertEquals("W:value:text/plain", last().body());
    }

    @Test
    void writer_interceptors_run_around_the_registered_writer() {
        WriterInterceptor upper = ctx -> {
            ctx.setEntity(new Payload(((Payload) ctx.getEntity()).value.toUpperCase()));
            ctx.proceed();
        };
        BodyApi client = builder().register(new PayloadWriter()).register(upper).build(BodyApi.class);

        client.sendJson(new Payload(VALUE));

        assertEquals("W:VALUE:application/json", last().body());
    }

    @Test
    void writer_io_failure_is_a_processing_exception() {
        BodyApi client = builder().register(new FailingWriter()).build(BodyApi.class);
        Payload payload = new Payload(VALUE);

        ProcessingException e = assertThrows(ProcessingException.class, () -> client.send(payload));
        assertInstanceOf(IOException.class, e.getCause());
    }

    @Test
    void entities_without_writer_are_sent_as_bytes_strings_or_json() {
        BodyApi client = builder().readTimeout(5, TimeUnit.SECONDS).build(BodyApi.class);

        client.sendBytes("raw".getBytes(StandardCharsets.UTF_8));
        assertEquals("raw", last().body());

        client.sendString("text");
        assertEquals("text", last().body());

        client.sendJson(new Payload(VALUE));
        assertEquals("{\"value\":\"value\"}", last().body());
        assertEquals(MediaType.APPLICATION_JSON, last().header("Content-Type"));
    }

    @Test
    void unreachable_server_is_a_processing_exception() {
        URI dead = server.baseUri();
        server.close();
        FactoryApi client = RestClientBuilder.newBuilder().baseUri(dead).build(FactoryApi.class);

        ProcessingException e = assertThrows(ProcessingException.class, client::get);
        assertInstanceOf(IOException.class, e.getCause());
    }

    @Test
    void interrupted_caller_gets_a_processing_exception_and_keeps_its_interrupt_flag() {
        FactoryApi client = builder().build(FactoryApi.class);

        Thread.currentThread().interrupt();
        try {
            ProcessingException e = assertThrows(ProcessingException.class, client::get);
            assertInstanceOf(InterruptedException.class, e.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
