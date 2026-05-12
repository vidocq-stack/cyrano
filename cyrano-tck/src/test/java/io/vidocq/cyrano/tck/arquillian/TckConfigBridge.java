/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.vidocq.cyrano.tck.arquillian;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pont de configuration statique entre le ShrinkWrap deployment TCK et
 * CyranoBaseUriResolver. Les propriétés extraites de META-INF/microprofile-config.properties
 * sont stockées ici et exportées comme propriétés système pour que
 * CyranoBaseUriResolver.defaultMpConfigLookup() les retrouve via son fallback
 * system-property.
 *
 * <p>Cycle de vie : {@link VaubanTckBootstrap#deploy(org.jboss.shrinkwrap.api.Archive)}
 * appelle {@link #setProperties(Properties, String)} avant de démarrer le container Vauban.
 * {@link VaubanTckBootstrap#undeploy()} appelle {@link #clear()} pour nettoyer les
 * system properties entre deux déploiements Arquillian.</p>
 */
final class TckConfigBridge {

    private static final Map<String, String> current = new ConcurrentHashMap<>();

    private TckConfigBridge() {}

    /**
     * Stocke les propriétés extraites de l'archive ShrinkWrap telles quelles.
     *
     * <p><strong>Pas de réécriture d'URL :</strong> les fixtures TCK construisent
     * dynamiquement leurs valeurs {@code mp-rest/url} via
     * {@code WiremockArquillianTest.getStringURL()} (système de propriétés
     * {@code wiremock.server.host}/{@code wiremock.server.port}, défaut
     * {@code localhost:8765}) qui pointent déjà sur notre {@link WireMockTestBackend}.
     * Réécrire systématiquement vers WireMock casserait les tests
     * (ConfigKeyTest, CDIURIvsURLConfigTest, ConfigKeyForMultipleInterfacesTest)
     * qui vérifient explicitement la valeur configurée via un filtre
     * {@code ReturnWithURLRequestFilter}.</p>
     *
     * @param props          propriétés extraites de {@code META-INF/microprofile-config.properties}
     * @param wireMockBaseUrl URL de base WireMock — conservé pour compatibilité (ignoré)
     */
    static void setProperties(Properties props, String wireMockBaseUrl) {
        current.clear();
        props.forEach((k, v) -> current.put(k.toString(), v.toString()));
    }

    /**
     * Supprime toutes les system properties posées par {@link #exportToSystemProperties()}
     * et vide le cache interne.
     */
    static void clear() {
        current.forEach((k, v) -> System.clearProperty(k));
        current.clear();
    }

    /**
     * Exporte toutes les propriétés stockées comme system properties, de sorte
     * que {@code CyranoBaseUriResolver.defaultMpConfigLookup()} les retrouve via
     * son fallback {@link System#getProperty(String)}.
     */
    static void exportToSystemProperties() {
        current.forEach(System::setProperty);
    }

    /**
     * Retourne la valeur d'une clé de configuration, ou {@link Optional#empty()} si absente.
     */
    static Optional<String> get(String key) {
        return Optional.ofNullable(current.get(key));
    }
}
