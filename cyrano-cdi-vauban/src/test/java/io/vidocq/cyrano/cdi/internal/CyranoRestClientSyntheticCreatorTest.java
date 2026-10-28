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
package io.vidocq.cyrano.cdi.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CyranoRestClientSyntheticCreator} and {@link CyranoRestClientSyntheticDisposer}: the creator of the
 * {@code @RestClient} synthetic beans reads the client options from MP Config (MP Rest Client 4.0 §5), keyed
 * by the interface name first, then by the {@code configKey}. Without an MP Config implementation in this
 * module's tests, the lookup falls back to system properties, which the tests set and clear.
 */
class CyranoRestClientSyntheticCreatorTest {

    private static final String CONFIG_KEY = "creator-test";
    private static final String FQN = ConfiguredApi.class.getName();
    private static final List<String> VALUES = List.of("a", "b");

    /** The client interface the synthetic bean stands for. */
    public interface ConfiguredApi {
        @GET
        @Path("/echo")
        String echo(@QueryParam("v") List<String> values);

        @GET
        @Path("/slow")
        String slow();

        @GET
        @Path("/redirect")
        String redirect();
    }

    /** A provider named in {@code <fqn>/mp-rest/providers}. */
    public static final class MarkerFilter implements ClientRequestFilter {
        @Override
        public void filter(ClientRequestContext requestContext) {
            requestContext.getHeaders().add("X-Provider", "marker");
        }
    }

    private final List<String> setProperties = new ArrayList<>();
    private HttpServer server;
    private String baseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> respond(exchange, 200,
                exchange.getRequestURI().getQuery() + "|" + exchange.getRequestHeaders().getFirst("X-Provider")));
        server.createContext("/slow", exchange -> {
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
            respond(exchange, 200, "late");
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUri + "/echo?v=moved");
            respond(exchange, 302, "");
        });
        server.start();
        baseUri = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServerAndClearConfig() {
        server.stop(0);
        setProperties.forEach(System::clearProperty);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void config(String key, String value) {
        System.setProperty(key, value);
        setProperties.add(key);
    }

    private ConfiguredApi create(String interfaceName, String annotationBaseUri, String configKey) {
        Map<String, Object> values = new HashMap<>();
        values.put(CyranoRestClientSyntheticCreator.PARAM_INTERFACE_NAME, interfaceName);
        values.put(CyranoRestClientSyntheticCreator.PARAM_BASE_URI, annotationBaseUri);
        values.put(CyranoRestClientSyntheticCreator.PARAM_CONFIG_KEY, configKey);
        return (ConfiguredApi) new CyranoRestClientSyntheticCreator().create(null, new MapParameters(values));
    }

    @Test
    void annotation_base_uri_and_default_options_are_used_without_config_spec_section5() {
        ConfiguredApi client = create(FQN, baseUri, null);

        assertEquals("v=a&v=b|null", client.echo(VALUES));
    }

    @Test
    void options_and_providers_configured_under_the_interface_name_apply_spec_section5() {
        config(FQN + "/mp-rest/url", baseUri);
        config(FQN + "/mp-rest/queryParamStyle", "ARRAY_PAIRS");
        config(FQN + "/mp-rest/providers", " , " + MarkerFilter.class.getName() + ",com.example.Missing");
        config(FQN + "/mp-rest/connectTimeout", "5000");

        ConfiguredApi client = create(FQN, "", CONFIG_KEY);

        assertEquals("v[]=a&v[]=b|marker", client.echo(VALUES));
    }

    @Test
    void options_configured_under_the_config_key_apply_spec_section5() {
        config(CONFIG_KEY + "/mp-rest/uri", baseUri);
        config(CONFIG_KEY + "/mp-rest/queryParamStyle", "COMMA_SEPARATED");
        config(CONFIG_KEY + "/mp-rest/providers", MarkerFilter.class.getName());
        config(CONFIG_KEY + "/mp-rest/followRedirects", "true");

        ConfiguredApi client = create(FQN, "http://unused.invalid", CONFIG_KEY);

        assertEquals("v=a,b|marker", client.echo(VALUES));
        assertEquals("v=moved|marker", client.redirect());
    }

    @Test
    void unknown_query_param_style_falls_back_to_multi_pairs() {
        config(FQN + "/mp-rest/queryParamStyle", "SOMETHING_ELSE");

        ConfiguredApi client = create(FQN, baseUri, "");

        assertEquals("v=a&v=b|null", client.echo(VALUES));
    }

    @Test
    void configured_read_timeout_interrupts_a_slow_call_spec_section5() {
        config(CONFIG_KEY + "/mp-rest/readTimeout", "200");
        ConfiguredApi client = create(FQN, baseUri, CONFIG_KEY);

        assertThrows(ProcessingException.class, client::slow);
    }

    @Test
    void invalid_timeout_names_the_property_it_was_read_from() {
        config(CONFIG_KEY + "/mp-rest/readTimeout", "soon");
        IllegalStateException fromKey = assertThrows(IllegalStateException.class,
                () -> create(FQN, baseUri, CONFIG_KEY));
        assertTrue(fromKey.getMessage().contains(CONFIG_KEY + "/mp-rest/readTimeout"), fromKey.getMessage());

        config(FQN + "/mp-rest/connectTimeout", "later");
        IllegalStateException fromFqn = assertThrows(IllegalStateException.class,
                () -> create(FQN, baseUri, CONFIG_KEY));
        assertTrue(fromFqn.getMessage().contains(FQN + "/mp-rest/connectTimeout"), fromFqn.getMessage());
    }

    @Test
    void unknown_interface_is_reported() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> create("com.example.NoSuchApi", baseUri, CONFIG_KEY));
        assertInstanceOf(ClassNotFoundException.class, e.getCause());
    }

    @Test
    void disposer_closes_the_client_spec_section8_1() {
        ConfiguredApi client = create(FQN, baseUri, CONFIG_KEY);

        new CyranoRestClientSyntheticDisposer().dispose(client, null, new MapParameters(Map.of()));

        assertThrows(IllegalStateException.class, () -> client.echo(VALUES));
    }

    @Test
    void disposer_ignores_non_closeable_instances_and_close_failures() {
        var disposer = new CyranoRestClientSyntheticDisposer();
        MapParameters none = new MapParameters(Map.of());
        Closeable failing = () -> {
            throw new IOException("already closed");
        };

        assertDoesNotThrow(() -> disposer.dispose("not closeable", null, none));
        assertDoesNotThrow(() -> disposer.dispose(failing, null, none));
    }

    /** {@link Parameters} backed by a map, as the container passes the {@code withParam} values. */
    private record MapParameters(Map<String, Object> values) implements Parameters {
        @Override
        public <T> T get(String key, Class<T> type) {
            return type.cast(values.get(key));
        }

        @Override
        public <T> T get(String key, Class<T> type, T defaultValue) {
            T value = get(key, type);
            return value != null ? value : defaultValue;
        }
    }
}
