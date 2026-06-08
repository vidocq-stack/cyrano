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
package io.vidocq.cyrano.tck.arquillian;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static configuration bridge between the TCK ShrinkWrap deployment and
 * {@code CyranoBaseUriResolver}. Properties extracted from
 * {@code META-INF/microprofile-config.properties} are stored here and exported
 * as system properties so that {@code CyranoBaseUriResolver.defaultMpConfigLookup()}
 * finds them again through its system-property fallback.
 *
 * <p>Lifecycle: {@link VaubanTckBootstrap#deploy(org.jboss.shrinkwrap.api.Archive)}
 * calls {@link #setProperties(Properties, String)} before starting the Vauban container.
 * {@link VaubanTckBootstrap#undeploy()} calls {@link #clear()} to clean up the
 * system properties between two Arquillian deployments.</p>
 */
final class TckConfigBridge {

    private static final Map<String, String> current = new ConcurrentHashMap<>();

    private TckConfigBridge() {}

    /**
     * Stores the properties extracted from the ShrinkWrap archive as is.
     *
     * <p><strong>No URL rewrite:</strong> the TCK fixtures build their
     * {@code mp-rest/url} values dynamically via
     * {@code WiremockArquillianTest.getStringURL()} (system properties
     * {@code wiremock.server.host}/{@code wiremock.server.port}, defaulting to
     * {@code localhost:8765}) which already point to our {@link WireMockTestBackend}.
     * Rewriting systematically to WireMock would break the tests
     * (ConfigKeyTest, CDIURIvsURLConfigTest, ConfigKeyForMultipleInterfacesTest)
     * which explicitly check the value configured via a
     * {@code ReturnWithURLRequestFilter} filter.</p>
     *
     * @param props properties extracted from {@code META-INF/microprofile-config.properties}
     * @param wireMockBaseUrl WireMock base URL — kept for compatibility (ignored)
     */
    static void setProperties(Properties props, String wireMockBaseUrl) {
        current.clear();
        props.forEach((k, v) -> current.put(k.toString(), v.toString()));
    }

    /**
     * Removes all system properties set by {@link #exportToSystemProperties()}
     * and clears the internal cache.
     */
    static void clear() {
        current.forEach((k, v) -> System.clearProperty(k));
        current.clear();
    }

    /**
     * Exports all properties stored as system properties, so
     * that {@code CyranoBaseUriResolver.defaultMpConfigLookup()} finds them via
     * its {@link System#getProperty(String)} fallback.
     */
    static void exportToSystemProperties() {
        current.forEach(System::setProperty);
    }

    /**
     * Returns the value of a configuration key, or {@link Optional#empty()} if absent.
     */
    static Optional<String> get(String key) {
        return Optional.ofNullable(current.get(key));
    }
}
