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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** MicroProfile Config is read directly when its API is present; the reflective lookup stays as the fallback. */
class CyranoBaseUriDirectConfigTest {

    @Test
    void theConfigurationIsReadWithoutReflectionWhenTheApiIsPresent() {
        assertTrue(CyranoBaseUriResolver.readsMpConfigDirectly());
    }

    @Test
    void bothLookupsFallBackToTheSystemProperty() {
        String key = "cyrano.test.direct." + System.nanoTime();
        System.setProperty(key, "v");
        try {
            assertEquals(Optional.of("v"), CyranoBaseUriResolver.defaultMpConfigLookup().apply(key));
            assertEquals(Optional.of("v"), CyranoBaseUriResolver.reflectiveMpConfigLookup().apply(key));
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    void aValueOfTheConfigurationIsReadDirectly() {
        var previous = safeInstance();
        org.eclipse.microprofile.config.spi.ConfigProviderResolver.setInstance(new OneValueResolver("cfg"));
        try {
            assertEquals(Optional.of("cfg"), CyranoBaseUriResolver.defaultMpConfigLookup().apply("any.key"));
        } finally {
            org.eclipse.microprofile.config.spi.ConfigProviderResolver.setInstance(previous);
        }
    }

    private static org.eclipse.microprofile.config.spi.ConfigProviderResolver safeInstance() {
        try {
            return org.eclipse.microprofile.config.spi.ConfigProviderResolver.instance();
        } catch (RuntimeException | java.util.ServiceConfigurationError _) {
            return null;
        }
    }

    /** A configuration holding one value for every key. */
    private static final class OneValueResolver extends org.eclipse.microprofile.config.spi.ConfigProviderResolver {
        private final org.eclipse.microprofile.config.Config config;

        OneValueResolver(String value) {
            config = (org.eclipse.microprofile.config.Config) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {org.eclipse.microprofile.config.Config.class},
                    (proxy, method, args) -> method.getName().equals("getOptionalValue") ? Optional.of(value) : null);
        }

        @Override
        public org.eclipse.microprofile.config.Config getConfig() {
            return config;
        }

        @Override
        public org.eclipse.microprofile.config.Config getConfig(ClassLoader loader) {
            return config;
        }

        @Override
        public org.eclipse.microprofile.config.spi.ConfigBuilder getBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerConfig(org.eclipse.microprofile.config.Config config, ClassLoader classLoader) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseConfig(org.eclipse.microprofile.config.Config config) {
            throw new UnsupportedOperationException();
        }
    }
}
