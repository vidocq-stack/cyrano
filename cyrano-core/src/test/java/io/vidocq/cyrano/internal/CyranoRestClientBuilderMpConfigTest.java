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
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Spec MP Rest Client 4.0 §8.1 — {@code microprofile.rest.client.disable.default.mapper}
 * may be provided through MicroProfile Config; the builder resolves it at
 * {@code build()} time unless an explicit builder property already set it
 * (TCK {@code DefaultExceptionMapperConfigTest}). The lookup is reflective:
 * cyrano-core has no compile-scope MP Config dependency.
 */
class CyranoRestClientBuilderMpConfigTest {

    static final String KEY = "microprofile.rest.client.disable.default.mapper";

    @Path("/simple")
    public interface SimpleApi {
        @GET
        String get();
    }

    private static Config registerConfig(Map<String, String> values) {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config config = resolver.getBuilder()
                .withSources(new MapSource(values))
                .forClassLoader(Thread.currentThread().getContextClassLoader())
                .build();
        resolver.registerConfig(config, Thread.currentThread().getContextClassLoader());
        return config;
    }

    @Test
    void disable_default_mapper_read_from_mp_config_spec_section8_1() {
        Config config = registerConfig(Map.of(KEY, "true"));
        try {
            RestClientBuilder builder = RestClientBuilder.newBuilder()
                    .baseUri(URI.create("http://localhost:9/"));
            builder.build(SimpleApi.class);
            assertEquals(Boolean.TRUE, builder.getConfiguration().getProperty(KEY),
                    "build() must resolve the disable-default-mapper key through MP Config");
        } finally {
            ConfigProviderResolver.instance().releaseConfig(config);
        }
    }

    @Test
    void explicit_builder_property_wins_over_mp_config_spec_section8_1() {
        Config config = registerConfig(Map.of(KEY, "true"));
        try {
            RestClientBuilder builder = RestClientBuilder.newBuilder()
                    .baseUri(URI.create("http://localhost:9/"))
                    .property(KEY, Boolean.FALSE);
            builder.build(SimpleApi.class);
            assertEquals(Boolean.FALSE, builder.getConfiguration().getProperty(KEY),
                    "an explicit builder property must win over MP Config");
        } finally {
            ConfigProviderResolver.instance().releaseConfig(config);
        }
    }

    @Test
    void absent_key_leaves_the_property_unset() {
        RestClientBuilder builder = RestClientBuilder.newBuilder()
                .baseUri(URI.create("http://localhost:9/"));
        builder.build(SimpleApi.class);
        assertNull(builder.getConfiguration().getProperty(KEY));
    }

    private record MapSource(Map<String, String> values) implements ConfigSource {
        @Override
        public Map<String, String> getProperties() {
            return values;
        }

        @Override
        public Set<String> getPropertyNames() {
            return values.keySet();
        }

        @Override
        public String getValue(String propertyName) {
            return values.get(propertyName);
        }

        @Override
        public String getName() {
            return "cyrano-mp-config-test-source";
        }

        @Override
        public int getOrdinal() {
            return 1000;
        }
    }
}
