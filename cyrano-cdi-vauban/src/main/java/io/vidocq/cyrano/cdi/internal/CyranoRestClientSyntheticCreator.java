/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
 * Creator pour les beans synthétiques produits par {@link CyranoRestClientCdiExtension}.
 *
 * <p>Au moment où le container demande l'instanciation du bean (premier
 * {@code @Inject @RestClient}), ce creator :</p>
 * <ol>
 *   <li>charge l'interface client à partir du paramètre {@code interfaceName} ;</li>
 *   <li>résout la base URI via {@link CyranoBaseUriResolver} — MP Config en
 *       priorité (si Ravel est sur le module-path), sinon
 *       {@code @RegisterRestClient(baseUri=...)} ;</li>
 *   <li>délègue à {@link RestClientBuilder#newBuilder()} qui produit un proxy
 *       Class-File API via la SPI {@code RestClientBuilderResolver}.</li>
 * </ol>
 *
 * <p>Spec MP Rest Client 4.0 §6.2 — « The container must use the
 * {@link RestClientBuilder} API to instantiate the rest client interface
 * proxy ». Cette voie unique garantit que les chemins programmatique et CDI
 * partagent exactement le même proxy.</p>
 */
public class CyranoRestClientSyntheticCreator implements SyntheticBeanCreator<Object> {

    /** Paramètre BCE : FQN de l'interface client (transporté entre @Synthesis et runtime). */
    public static final String PARAM_INTERFACE_NAME = "interfaceName";

    /** Paramètre BCE : valeur littérale de {@code @RegisterRestClient.baseUri()}. */
    public static final String PARAM_BASE_URI = "baseUri";

    /** Paramètre BCE : valeur littérale de {@code @RegisterRestClient.configKey()}. */
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

        // Spec §5 — queryParamStyle via MP Config
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

        // Spec §5 — providers via MP Config (comma-separated FQNs)
        registerConfigProviders(interfaceFqn, configKey, configLookup, builder);

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
                // provider not on classpath — skip silently
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
            throw new IllegalStateException("Cyrano CDI : valeur MP Config invalide pour '"
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
                    "Cyrano CDI : interface @RegisterRestClient introuvable '" + fqn + "'", e);
        }
    }
}

