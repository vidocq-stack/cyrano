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

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.ext.QueryParamStyle;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Creator for synthetic beans produced by {@link CyranoRestClientCdiExtension}.
 *
 * <p>At the time the container requests bean instantiation (first
 * {@code @Inject @RestClient}), this creator:</p>
 * <ol>
 * <li>loads the client interface from {@code interfaceName};</li>
 * <li>resolves the base URI via {@link CyranoBaseUriResolver} — MP Config takes
 * priority (if Ravel is on the module path), otherwise
 *       {@code @RegisterRestClient(baseUri=...)} ;</li>
 * <li>delegates to {@link RestClientBuilder#newBuilder()} which produces a proxy
 *       through the Class-File API via the SPI {@code RestClientBuilderResolver}.</li>
 * </ol>
 *
 * <p>Spec MP Rest Client 4.0 §6.2 — « The container must use the
 * {@link RestClientBuilder} API to instantiate the rest client interface
 * proxy". This single path ensures that the programmatic and CDI paths
 * share exactly the same proxy.</p>
 */
public class CyranoRestClientSyntheticCreator implements SyntheticBeanCreator<Object> {

    /** BCE parameter: client interface FQN (carried between {@code @Synthesis} and runtime). */
    public static final String PARAM_INTERFACE_NAME = "interfaceName";

    /** BCE parameter: literal value of {@code @RegisterRestClient.baseUri()}. */
    public static final String PARAM_BASE_URI = "baseUri";

    /** BCE parameter: literal value of {@code @RegisterRestClient.configKey()}. */
    public static final String PARAM_CONFIG_KEY = "configKey";

    @Override
    public Object create(Instance<Object> lookup, Parameters params) {
        String interfaceFqn = params.get(PARAM_INTERFACE_NAME, String.class);
        String baseUriValue = orEmpty(params.get(PARAM_BASE_URI, String.class));
        String configKey = orEmpty(params.get(PARAM_CONFIG_KEY, String.class));

        Class<?> iface = loadInterface(interfaceFqn);
        URI baseUri = CyranoBaseUriResolver.resolveWithDefaultMpConfig(
                interfaceFqn, baseUriValue, configKey);

        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(baseUri);

        Function<String, Optional<String>> configLookup = CyranoBaseUriResolver.defaultMpConfigLookup();

        //Spec §5 — queryParamStyle via MP Config
        QueryParamStyle qps = resolveQueryParamStyle(interfaceFqn, configKey, configLookup);
        if (qps != null) {
            builder.queryParamStyle(qps);
        }

        Boolean followRedirects = resolveBooleanProperty(interfaceFqn, configKey, configLookup, "/mp-rest/followRedirects");
        if (followRedirects != null) {
            builder.followRedirects(followRedirects);
        }

        Long connectTimeoutMs = resolveLongProperty(interfaceFqn, configKey, configLookup, "/mp-rest/connectTimeout");
        if (connectTimeoutMs != null) {
            builder.connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS);
        }

        Long readTimeoutMs = resolveLongProperty(interfaceFqn, configKey, configLookup, "/mp-rest/readTimeout");
        if (readTimeoutMs != null) {
            builder.readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS);
        }

        //Spec §5 — providers via MP Config (comma-separated FQNs)
        registerConfigProviders(interfaceFqn, configKey, configLookup, builder);

        //Spec §5.6 — trustStore/keyStore/hostnameVerifier via MP Config
        CyranoSslConfigResolver.applySslConfig(interfaceFqn, configKey, configLookup, builder);

        return builder.build(iface);
    }

    private static String orEmpty(String v) { return v == null ? "" : v; }

    /** Spec MP Rest Client 4.0 §5 — providers via MP Config. */
    private static void registerConfigProviders(
            String fqn, String configKey,
            Function<String, Optional<String>> lookup, RestClientBuilder builder) {
        Optional<String> val = lookup.apply(fqn + "/mp-rest/providers");
        if (val.isEmpty() || val.get().isBlank()) {
            if (configKey != null && !configKey.isBlank()) {
                val = lookup.apply(configKey + "/mp-rest/providers");
            }
        }
        if (val.isEmpty() || val.get().isBlank()) return;
        for (String providerFqn : val.get().split(",")) {
            String trimmed = providerFqn.trim();
            if (trimmed.isEmpty()) continue;
            try {
                Class<?> providerClass = Class.forName(trimmed, true,
                        Thread.currentThread().getContextClassLoader());
                builder.register(providerClass);
            } catch (ClassNotFoundException e) {
                //provider not on classpath — skip silently
            }
        }
    }

    /** Spec MP Rest Client 4.0 §5 — queryParamStyle via MP Config. */
    private static QueryParamStyle resolveQueryParamStyle(
            String fqn, String configKey, Function<String, Optional<String>> lookup) {
        Optional<String> val = resolveProperty(fqn, configKey, lookup, "/mp-rest/queryParamStyle");
        if (val.isEmpty() || val.get().isBlank()) return null;
        return switch (val.get().trim()) {
            case "COMMA_SEPARATED" -> QueryParamStyle.COMMA_SEPARATED;
            case "ARRAY_PAIRS"     -> QueryParamStyle.ARRAY_PAIRS;
            default                -> QueryParamStyle.MULTI_PAIRS;
        };
    }

    private static Boolean resolveBooleanProperty(
            String fqn, String configKey, Function<String, Optional<String>> lookup, String suffix) {
        Optional<String> val = resolveProperty(fqn, configKey, lookup, suffix);
        if (val.isEmpty()) return null;
        return Boolean.parseBoolean(val.get().trim());
    }

    private static Long resolveLongProperty(
            String fqn, String configKey, Function<String, Optional<String>> lookup, String suffix) {
        Optional<String> val = resolveProperty(fqn, configKey, lookup, suffix);
        if (val.isEmpty()) return null;
        try {
            return Long.parseLong(val.get().trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Cyrano CDI: invalid MP Config value for '"
                    + effectivePropertyName(fqn, configKey, lookup, suffix) + "' = '" + val.get() + "'", e);
        }
    }

    private static Optional<String> resolveProperty(
            String fqn, String configKey, Function<String, Optional<String>> lookup, String suffix) {
        Optional<String> fromFqn = lookup.apply(fqn + suffix);
        if (fromFqn.isPresent() && !fromFqn.get().isBlank()) return fromFqn;
        if (configKey != null && !configKey.isBlank()) {
            Optional<String> fromKey = lookup.apply(configKey + suffix);
            if (fromKey.isPresent() && !fromKey.get().isBlank()) return fromKey;
        }
        return Optional.empty();
    }

    private static String effectivePropertyName(
            String fqn, String configKey, Function<String, Optional<String>> lookup, String suffix) {
        Optional<String> fromFqn = lookup.apply(fqn + suffix);
        if (fromFqn.isPresent() && !fromFqn.get().isBlank()) return fqn + suffix;
        return configKey + suffix;
    }

    private static Class<?> loadInterface(String fqn) {
        try {
            return Class.forName(fqn, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Cyrano CDI: @RegisterRestClient interface not found '" + fqn + "'", e);
        }
    }
}
