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

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Optional;
import java.util.function.Function;

/**
 * Résolveur de base URI pour une interface {@code @RegisterRestClient} — spec
 * MicroProfile Rest Client 4.0 §5 « Configuration ».
 *
 * <p>Ordre de priorité (du plus fort au plus faible) :</p>
 * <ol>
 *   <li>{@code <interface.fqn>/mp-rest/url} via MicroProfile Config (Ravel) ;</li>
 *   <li>{@code <configKey>/mp-rest/url} via MicroProfile Config (Ravel), si
 *       {@code @RegisterRestClient(configKey=...)} est renseigné ;</li>
 *   <li>{@code @RegisterRestClient(baseUri=...)} ;</li>
 *   <li>échec : {@link IllegalStateException} (mapper en
 *       {@code DeploymentException} côté BCE).</li>
 * </ol>
 *
 * <p>MP Config est <strong>optionnel</strong> : si l'API
 * {@code org.eclipse.microprofile.config.ConfigProvider} n'est pas chargeable
 * (Ravel absent du module-path), la résolution se rabat silencieusement sur
 * l'annotation. Ce comportement satisfait l'exigence AGENTS.md « dégrader
 * gracieusement sans NPE si Ravel absent ».</p>
 *
 * <p>Le composant est conçu pour être unit-testable : la fonction de lookup
 * MP Config est injectable via {@link #resolve(String, String, String, Function)}.</p>
 */
public final class CyranoBaseUriResolver {

    /** Suffixe MP Config — spec §5. */
    public static final String MP_REST_URL_SUFFIX = "/mp-rest/url";

    /** Alias URI — spec §5 (même priorité que /mp-rest/url, /uri prévaut si les deux sont définis). */
    public static final String MP_REST_URI_SUFFIX = "/mp-rest/uri";

    private CyranoBaseUriResolver() {
        // utility — pas d'instanciation
    }

    /**
     * Résout la base URI à partir des informations de l'annotation et de la
     * fonction de lookup MP Config fournie.
     *
     * @param interfaceFqn FQN de l'interface client (non null)
     * @param baseUriValue valeur de {@code @RegisterRestClient.baseUri()} (jamais null, peut être {@code ""})
     * @param configKey    valeur de {@code @RegisterRestClient.configKey()} (jamais null, peut être {@code ""})
     * @param configLookup fonction qui retourne la valeur MP Config pour une clé (ou {@link Optional#empty()})
     * @return l'URI résolue
     * @throws IllegalStateException si aucune source n'est disponible
     */
    public static URI resolve(
            String interfaceFqn,
            String baseUriValue,
            String configKey,
            Function<String, Optional<String>> configLookup) {

        // §5 priorité 1 : <fqn>/mp-rest/url ou <fqn>/mp-rest/uri
        Optional<String> fromFqn = lookupUrlOrUri(configLookup, interfaceFqn);
        if (fromFqn.isPresent() && !fromFqn.get().isBlank()) {
            return URI.create(fromFqn.get());
        }

        // §5 priorité 2 : <configKey>/mp-rest/url ou <configKey>/mp-rest/uri
        if (configKey != null && !configKey.isBlank()) {
            Optional<String> fromKey = lookupUrlOrUri(configLookup, configKey);
            if (fromKey.isPresent() && !fromKey.get().isBlank()) {
                return URI.create(fromKey.get());
            }
        }

        // §5 priorité 3 : @RegisterRestClient(baseUri=...)
        if (baseUriValue != null && !baseUriValue.isBlank()) {
            return URI.create(baseUriValue);
        }

        throw new IllegalStateException(
                "Cyrano CDI : aucune base URI résolue pour '" + interfaceFqn
                + "' (ni MP Config '" + interfaceFqn + MP_REST_URL_SUFFIX + "' / '"
                + interfaceFqn + MP_REST_URI_SUFFIX + "'"
                + (configKey != null && !configKey.isBlank()
                        ? " / '" + configKey + MP_REST_URL_SUFFIX + "' / '" + configKey + MP_REST_URI_SUFFIX + "'"
                        : "")
                + ", ni @RegisterRestClient(baseUri=...)) — spec MP Rest Client 4.0 §5");
    }

    /**
     * Cherche {@code prefix/mp-rest/url} puis {@code prefix/mp-rest/uri}.
     * Retourne la première valeur non vide trouvée.
     */
    private static Optional<String> lookupUrlOrUri(Function<String, Optional<String>> configLookup, String prefix) {
        Optional<String> uri = configLookup.apply(prefix + MP_REST_URI_SUFFIX);
        if (uri.isPresent() && !uri.get().isBlank()) return uri;
        return configLookup.apply(prefix + MP_REST_URL_SUFFIX);
    }

    /**
     * Variante production : tente d'utiliser MP Config via réflexion (Ravel
     * détecté à l'exécution) ; à défaut, ne retourne jamais de valeur MP Config.
     */
    public static URI resolveWithDefaultMpConfig(
            String interfaceFqn, String baseUriValue, String configKey) {
        return resolve(interfaceFqn, baseUriValue, configKey, defaultMpConfigLookup());
    }

    /**
     * Fonction de lookup MP Config résolue via réflexion — détache
     * {@code cyrano-cdi-vauban} de toute dépendance compile sur
     * {@code microprofile-config-api}. Si l'API n'est pas accessible, retourne
     * une fonction qui renvoie systématiquement {@link Optional#empty()}.
     */
    static Function<String, Optional<String>> defaultMpConfigLookup() {
        // Cache la résolution réflexive en lambda — l'échec d'introspection
        // (ClassNotFound, NoClassDefFound) provoque un fallback sur les system properties.
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
                // Fallback : system property (utilisé par TckConfigBridge en mode TCK)
                return Optional.ofNullable(System.getProperty(key));
            };
        } catch (ClassNotFoundException | NoClassDefFoundError | NoSuchMethodException ignored) {
            // MP Config absent — fallback complet sur les system properties
            return key -> Optional.ofNullable(System.getProperty(key));
        }
    }
}

