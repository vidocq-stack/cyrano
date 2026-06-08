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

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Optional;
import java.util.function.Function;

/**
 * Base URI resolver for {@code @RegisterRestClient} interfaces — MicroProfile
 * Rest Client 4.0 §5 "Configuration".
 *
 * <p>Priority order (from highest to lowest):</p>
 * <ol>
 *   <li>{@code <interface.fqn>/mp-rest/url} via MicroProfile Config (Ravel) ;</li>
 *   <li>{@code <configKey>/mp-rest/url} via MicroProfile Config (Ravel), if
 * {@code @RegisterRestClient(configKey=...)} is specified;</li>
 *   <li>{@code @RegisterRestClient(baseUri=...)} ;</li>
 * <li> failure: {@link IllegalStateException}
 * {@code DeploymentException} on the ECB side).</li>
 * </ol>
 *
 * <p>MP Config is <strong>optional</strong>: if the API
 * {@code org.eclipse.microprofile.config.ConfigProvider} is not loadable
 * (Ravel absent from the module path), resolution silently falls back to
 * the annotation. This behaviour satisfies the AGENTS.md requirement "degradation
 * free of NPE if Ravel absent".</p>
 *
 * <p>The component is designed to be unit-testable: the MP Config lookup function
 * is injectable via {@link #resolve(String, String, String, Function)}.</p>
 */
public final class CyranoBaseUriResolver {

    /** MP Config suffix — spec §5. */
    public static final String MP_REST_URL_SUFFIX = "/mp-rest/url";

    /** URI alias — spec §5 (same priority as /mp-rest/url, with /uri taking precedence if both are defined). */
    public static final String MP_REST_URI_SUFFIX = "/mp-rest/uri";

    private CyranoBaseUriResolver() {
        //utility — no detailing
    }

    /**
     * Resolves the base URI from the annotation and the
     * provided MP Config lookup function.
     *
     * @param interfaceFqn FQN of the client interface (non-null)
     * @param baseUriValue value of {@code @RegisterRestClient.baseUri()} (never null, can be {@code ""})
     * @param configKey value of {@code @RegisterRestClient.configKey()} (never null, can be {@code ""})
     * @param configLookup function that returns the MP Config value for a key (or {@link Optional#empty()})
     * @return resolved URI
     * @throws IllegalStateException if no source is available
     */
    public static URI resolve(
            String interfaceFqn,
            String baseUriValue,
            String configKey,
            Function<String, Optional<String>> configLookup) {

        //§5 priority 1: <fqn>/mp-rest/url or <fqn>/mp-rest/uri
        Optional<String> fromFqn = lookupUrlOrUri(configLookup, interfaceFqn);
        if (fromFqn.isPresent() && !fromFqn.get().isBlank()) {
            return URI.create(fromFqn.get());
        }

        //§5 priority 2: <configKey>/mp-rest/url or <configKey>/mp-rest/uri
        if (configKey != null && !configKey.isBlank()) {
            Optional<String> fromKey = lookupUrlOrUri(configLookup, configKey);
            if (fromKey.isPresent() && !fromKey.get().isBlank()) {
                return URI.create(fromKey.get());
            }
        }

        //§5 priority 3: @RegisterRestClient(baseUri=...)
        if (baseUriValue != null && !baseUriValue.isBlank()) {
            return URI.create(baseUriValue);
        }

        throw new IllegalStateException(
                "Cyrano CDI: no base URI resolved for '" + interfaceFqn
                + "' (ni MP Config '" + interfaceFqn + MP_REST_URL_SUFFIX + "' / '"
                + interfaceFqn + MP_REST_URI_SUFFIX + "'"
                + (configKey != null && !configKey.isBlank()
                        ? " / '" + configKey + MP_REST_URL_SUFFIX + "' / '" + configKey + MP_REST_URI_SUFFIX + "'"
                        : "")
                + ", ni @RegisterRestClient(baseUri=...)) — spec MP Rest Client 4.0 §5");
    }

    /**
     * Searches {@code prefix/mp-rest/url} then {@code prefix/mp-rest/uri}.
     * Returns the first non-empty value found.
     */
    private static Optional<String> lookupUrlOrUri(Function<String, Optional<String>> configLookup, String prefix) {
        Optional<String> uri = configLookup.apply(prefix + MP_REST_URI_SUFFIX);
        if (uri.isPresent() && !uri.get().isBlank()) return uri;
        return configLookup.apply(prefix + MP_REST_URL_SUFFIX);
    }

    /**
     * Production variant: tries to use MP Config via reflection (Ravel
     * detected at runtime); if not available, never returns an MP Config value.
     */
    public static URI resolveWithDefaultMpConfig(
            String interfaceFqn, String baseUriValue, String configKey) {
        return resolve(interfaceFqn, baseUriValue, configKey, defaultMpConfigLookup());
    }

    /**
     * Resolved MP Config lookup function via reflection — detached
     * {@code cyrano-cdi-vauban} from any compile-time dependency on
     * {@code microprofile-config-api}. If the API is not accessible, returns
     * a function that systematically returns {@link Optional#empty()}.
     */
    static Function<String, Optional<String>> defaultMpConfigLookup() {
        //Hide reflective resolution in lambda — introspection failure
        // (ClassNotFound, NoClassDefFound) triggers a fallback to system properties.
        try {
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            ClassLoader self = CyranoBaseUriResolver.class.getClassLoader();
            Class<?> providerClass = Class.forName(
                    "org.eclipse.microprofile.config.ConfigProvider", true,
                    tccl != null ? tccl : self);
            Class<?> configClass = Class.forName("org.eclipse.microprofile.config.Config");
            Method configAccessor;
            try {
                configAccessor = providerClass.getMethod("getConfig", ClassLoader.class);
            } catch (NoSuchMethodException noClMethod) {
                configAccessor = providerClass.getMethod("getConfig");
            }
            final Method getConfig = configAccessor;
            final Method getOptionalValue = configClass.getMethod("getOptionalValue", String.class, Class.class);
            return key -> {
                try {
                    Object config;
                    if (getConfig.getParameterCount() == 1) {
                        ClassLoader cl = Thread.currentThread().getContextClassLoader();
                        if (cl == null) cl = CyranoBaseUriResolver.class.getClassLoader();
                        config = getConfig.invoke(null, cl);
                    } else {
                        config = getConfig.invoke(null);
                    }
                    @SuppressWarnings("unchecked")
                    Optional<String> value = (Optional<String>) getOptionalValue.invoke(config, key, String.class);
                    if (value != null && value.isPresent()) return value;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // fall through to system property fallback
                }
                //Fallback: system property (used by TckConfigBridge in TCK mode)
                return Optional.ofNullable(System.getProperty(key));
            };
        } catch (ClassNotFoundException | NoClassDefFoundError | NoSuchMethodException ignored) {
            //MP Config absent — complete fallback to system properties
            return key -> Optional.ofNullable(System.getProperty(key));
        }
    }
}
