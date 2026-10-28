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
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.RestClientDefinitionException;
import org.eclipse.microprofile.rest.client.ext.QueryParamStyle;
import org.eclipse.microprofile.rest.client.spi.RestClientListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CyranoRestClientBuilder} options and provider registration (MP Rest Client 4.0 §5,
 * Jakarta REST {@code Configurable}).
 */
class CyranoRestClientBuilderTest {

    private static final URI BASE = URI.create("http://localhost:1");

    interface PingApi {
        @GET
        String ping();
    }

    public static final class FirstFilter implements ClientRequestFilter, ClientResponseFilter {
        @Override
        public void filter(ClientRequestContext requestContext) {
            // test double: the order of the registered filters is asserted
        }

        @Override
        public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
            // test double: the order of the registered filters is asserted
        }
    }

    public static final class SecondFilter implements ClientRequestFilter, ClientResponseFilter {
        @Override
        public void filter(ClientRequestContext requestContext) {
            // test double: the order of the registered filters is asserted
        }

        @Override
        public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
            // test double: the order of the registered filters is asserted
        }
    }

    public static final class ThirdFilter implements ClientRequestFilter {
        @Override
        public void filter(ClientRequestContext requestContext) {
            // test double: the order of the registered filters is asserted
        }
    }

    public static final class Interceptors implements ReaderInterceptor, WriterInterceptor {
        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) {
            return null;
        }

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) {
            // test double: only its registration is asserted
        }
    }

    /** Registers one provider through every {@link FeatureContext#register} overload. */
    public static final class RegisteringFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.property("feature.ran", true)
                    .register(FirstFilter.class)
                    .register(SecondFilter.class, 10)
                    .register(ThirdFilter.class, ClientRequestFilter.class)
                    .register(Interceptors.class, Map.of(ReaderInterceptor.class, 7));
            return context.getConfiguration() != null;
        }
    }

    /** Registers instances through every {@link FeatureContext#register} overload. */
    public static final class InstanceFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.register(new FirstFilter())
                    .register(new SecondFilter(), 20)
                    .register(new ThirdFilter(), ClientRequestFilter.class)
                    .register(new Interceptors(), Map.of(WriterInterceptor.class, 3));
            return false;
        }
    }

    public static final class FailingFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            throw new IllegalStateException("feature failure");
        }
    }

    private static CyranoClientConfiguration configurationOf(RestClientBuilder builder) {
        return (CyranoClientConfiguration) builder.getConfiguration();
    }

    @Test
    void class_registrations_detect_the_contracts_and_keep_the_priorities() {
        RestClientBuilder builder = new CyranoRestClientBuilder()
                .register(FirstFilter.class)
                .register(SecondFilter.class, 10)
                .register(ThirdFilter.class, ClientRequestFilter.class)
                .register(Interceptors.class, Map.of(ReaderInterceptor.class, 7));
        Configuration configuration = builder.getConfiguration();

        assertEquals(Map.of(ClientRequestFilter.class, Priorities.USER, ClientResponseFilter.class, Priorities.USER),
                configuration.getContracts(FirstFilter.class));
        assertEquals(Map.of(ClientRequestFilter.class, 10, ClientResponseFilter.class, 10),
                configuration.getContracts(SecondFilter.class));
        assertEquals(Map.of(ClientRequestFilter.class, Priorities.USER), configuration.getContracts(ThirdFilter.class));
        assertEquals(Map.of(ReaderInterceptor.class, 7), configuration.getContracts(Interceptors.class));
        assertTrue(configuration.isRegistered(FirstFilter.class));
        assertTrue(configuration.getContracts(String.class).isEmpty());
        assertEquals(4, configuration.getClasses().size());
    }

    @Test
    void request_filters_run_by_ascending_priority_and_response_filters_by_descending_priority() {
        CyranoClientConfiguration configuration = configurationOf(new CyranoRestClientBuilder()
                .register(new SecondFilter(), 200)
                .register(new FirstFilter(), 100)
                .register(new ThirdFilter(), Map.of(ClientRequestFilter.class, 150)));

        List<ClientRequestFilter> requestFilters = configuration.getRequestFilters();
        assertEquals(List.of(FirstFilter.class, ThirdFilter.class, SecondFilter.class),
                requestFilters.stream().map(Object::getClass).toList());

        List<ClientResponseFilter> responseFilters = configuration.getResponseFilters();
        assertEquals(List.of(SecondFilter.class, FirstFilter.class),
                responseFilters.stream().map(Object::getClass).toList());
    }

    @Test
    void a_single_contract_priority_applies_to_any_contract_and_no_priority_means_user() {
        CyranoClientConfiguration configuration = configurationOf(new CyranoRestClientBuilder()
                .register(new SecondFilter(), Map.of(ClientResponseFilter.class, 1))
                .register(new FirstFilter(), Map.of()));

        // SecondFilter: the only declared priority (1) also orders it as a request filter.
        assertEquals(List.of(SecondFilter.class, FirstFilter.class),
                configuration.getRequestFilters().stream().map(Object::getClass).toList());
    }

    @Test
    void instance_registrations_are_listed_as_instances_and_interceptors() {
        Interceptors interceptors = new Interceptors();
        FirstFilter filter = new FirstFilter();
        CyranoClientConfiguration configuration = configurationOf(new CyranoRestClientBuilder()
                .register(filter)
                .register(interceptors, ReaderInterceptor.class, WriterInterceptor.class));

        assertTrue(configuration.isRegistered(filter));
        assertTrue(configuration.getInstances().contains(interceptors));
        assertEquals(List.of(interceptors), configuration.getReaderInterceptors());
        assertEquals(List.of(interceptors), configuration.getWriterInterceptors());
        assertEquals(Map.of(ReaderInterceptor.class, Priorities.USER, WriterInterceptor.class, Priorities.USER),
                configuration.getContracts(Interceptors.class));
    }

    @Test
    void features_register_providers_through_the_feature_context_spec_section5() {
        RegisteringFeature feature = new RegisteringFeature();
        RestClientBuilder builder = new CyranoRestClientBuilder().baseUri(BASE)
                .register(feature)
                .register(FailingFeature.class);

        PingApi client = builder.build(PingApi.class);

        Configuration configuration = builder.getConfiguration();
        assertNotNull(client);
        assertEquals(true, configuration.getProperty("feature.ran"));
        assertTrue(configuration.isEnabled(feature));
        assertTrue(configuration.isEnabled(RegisteringFeature.class));
        assertFalse(configuration.isEnabled(FailingFeature.class));
        assertEquals(Map.of(ClientRequestFilter.class, 10, ClientResponseFilter.class, 10),
                configuration.getContracts(SecondFilter.class));
        assertEquals(Map.of(ReaderInterceptor.class, 7), configuration.getContracts(Interceptors.class));
    }

    @Test
    void a_feature_registers_provider_instances_and_may_decline_to_be_enabled() {
        // A separate builder: features run in no defined order, and both features register the same classes.
        RestClientBuilder builder = new CyranoRestClientBuilder().baseUri(BASE).register(InstanceFeature.class);

        builder.build(PingApi.class);

        Configuration configuration = builder.getConfiguration();
        assertFalse(configuration.isEnabled(InstanceFeature.class));
        assertEquals(Map.of(ClientRequestFilter.class, 20, ClientResponseFilter.class, 20),
                configuration.getContracts(SecondFilter.class));
        assertEquals(Map.of(ClientRequestFilter.class, Priorities.USER), configuration.getContracts(ThirdFilter.class));
        assertEquals(Map.of(WriterInterceptor.class, 3), configuration.getContracts(Interceptors.class));
    }

    @Test
    void transport_options_are_stored_in_the_configuration() throws NoSuchAlgorithmException {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        SSLContext sslContext = SSLContext.getDefault();
        HostnameVerifier verifier = (_, _) -> true;
        try {
            CyranoClientConfiguration configuration = configurationOf(new CyranoRestClientBuilder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .executorService(executor)
                    .sslContext(sslContext)
                    .hostnameVerifier(verifier)
                    .followRedirects(true)
                    .proxyAddress("proxy.local", 3128)
                    .queryParamStyle(QueryParamStyle.COMMA_SEPARATED)
                    .header("X-Builder", "on")
                    .property("p", "v"));

            assertEquals(2000, configuration.getConnectTimeoutMs());
            assertEquals(3000, configuration.getReadTimeoutMs());
            assertSame(executor, configuration.getExecutorService());
            assertSame(sslContext, configuration.getSslContext());
            assertSame(verifier, configuration.getHostnameVerifier());
            assertTrue(configuration.hasSslConfiguration());
            assertTrue(configuration.isFollowRedirects());
            assertEquals("proxy.local", configuration.getProxyHost());
            assertEquals(3128, configuration.getProxyPort());
            assertEquals(QueryParamStyle.COMMA_SEPARATED, configuration.getQueryParamStyle());
            assertEquals(Map.of("X-Builder", "on"), configuration.getBuilderHeaders());
            assertEquals("v", configuration.getProperty("p"));
            assertEquals(Map.of("p", "v"), configuration.getProperties());
            assertTrue(configuration.getPropertyNames().contains("p"));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void key_and_trust_stores_count_as_ssl_configuration() throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);

        CyranoClientConfiguration trustOnly = configurationOf(new CyranoRestClientBuilder().trustStore(store));
        CyranoClientConfiguration keyOnly = configurationOf(new CyranoRestClientBuilder().keyStore(store, "secret"));

        assertTrue(trustOnly.hasSslConfiguration());
        assertSame(store, trustOnly.getTrustStore());
        assertTrue(keyOnly.hasSslConfiguration());
        assertSame(store, keyOnly.getKeyStore());
        assertEquals("secret", keyOnly.getKeyStorePassword());
        assertFalse(configurationOf(new CyranoRestClientBuilder()).hasSslConfiguration());
    }

    @Test
    void a_null_query_param_style_means_multi_pairs() {
        CyranoClientConfiguration configuration = configurationOf(new CyranoRestClientBuilder().queryParamStyle(null));
        assertEquals(QueryParamStyle.MULTI_PAIRS, configuration.getQueryParamStyle());
    }

    @Test
    void base_url_is_converted_to_a_uri() throws Exception {
        PingApi client = new CyranoRestClientBuilder().baseUrl(BASE.toURL()).build(PingApi.class);
        assertNotNull(client);
    }

    @Test
    void invalid_builder_arguments_are_rejected() {
        CyranoRestClientBuilder builder = new CyranoRestClientBuilder();

        assertThrows(IllegalArgumentException.class, () -> builder.executorService(null));
        assertThrows(IllegalArgumentException.class, () -> builder.proxyAddress(" ", 80));
        assertThrows(NullPointerException.class, () -> builder.header("X-Null", null));
    }

    @Test
    void build_requires_a_base_uri_and_an_interface_spec_section5() {
        CyranoRestClientBuilder withoutUri = new CyranoRestClientBuilder();
        assertThrows(IllegalStateException.class, () -> withoutUri.build(PingApi.class));

        RestClientBuilder withUri = new CyranoRestClientBuilder().baseUri(BASE);
        assertThrows(RestClientDefinitionException.class, () -> withUri.build(String.class));
    }

    /** Counts the clients it is told about; listed in a {@code services/} file, see the test below. */
    public static final class CountingListener implements RestClientListener {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public void onNewClient(Class<?> serviceInterface, RestClientBuilder builder) {
            CALLS.incrementAndGet();
        }
    }

    @Test
    void listeners_listed_in_a_services_file_of_the_context_class_loader_are_notified(@TempDir Path dir)
            throws IOException {
        Path services = Files.createDirectories(dir.resolve("services"));
        Files.writeString(services.resolve(RestClientListener.class.getName()), String.join("\n",
                "# comment line",
                "",
                "io.vidocq.cyrano.internal.DoesNotExist",
                "java.lang.String",
                CountingListener.class.getName() + " # trailing comment",
                CountingListener.class.getName()));
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        int before = CountingListener.CALLS.get();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            new CyranoRestClientBuilder().baseUri(BASE).build(PingApi.class);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        assertEquals(before + 1, CountingListener.CALLS.get());
    }

    @Test
    void the_configuration_is_a_client_side_configuration() {
        Configuration configuration = new CyranoRestClientBuilder().getConfiguration();
        assertEquals(jakarta.ws.rs.RuntimeType.CLIENT, configuration.getRuntimeType());
        assertNull(configuration.getProperty("absent"));
        assertFalse(configuration.isRegistered(new Object()));
    }
}
