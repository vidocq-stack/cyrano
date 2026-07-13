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

import org.eclipse.microprofile.rest.client.RestClientBuilder;

import javax.net.ssl.HostnameVerifier;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.KeyStore;
import java.util.Optional;
import java.util.function.Function;

/**
 * Applies the SSL-related MP Config keys to a {@link RestClientBuilder} —
 * spec MP Rest Client 4.0 §5.6:
 * {@code <fqn>/mp-rest/trustStore[Type|Password]},
 * {@code <fqn>/mp-rest/keyStore[Type|Password]} and
 * {@code <fqn>/mp-rest/hostnameVerifier}. Each key also resolves through the
 * {@code @RegisterRestClient(configKey)} form. Store locations accept
 * {@code classpath:/…} (resolved on the context class loader — TCK archives
 * pack their stores in the deployment) and any URL form ({@code file:/…}).
 */
final class CyranoSslConfigResolver {

    private static final String DEFAULT_STORE_TYPE = "JKS";

    private CyranoSslConfigResolver() {
    }

    static void applySslConfig(String fqn, String configKey,
                               Function<String, Optional<String>> lookup, RestClientBuilder builder) {
        String trustStoreLocation = resolve(fqn, configKey, lookup, "/mp-rest/trustStore");
        if (trustStoreLocation != null) {
            String type = orDefault(resolve(fqn, configKey, lookup, "/mp-rest/trustStoreType"), DEFAULT_STORE_TYPE);
            String password = resolve(fqn, configKey, lookup, "/mp-rest/trustStorePassword");
            builder.trustStore(loadStore(trustStoreLocation, type, password));
        }

        String keyStoreLocation = resolve(fqn, configKey, lookup, "/mp-rest/keyStore");
        if (keyStoreLocation != null) {
            String type = orDefault(resolve(fqn, configKey, lookup, "/mp-rest/keyStoreType"), DEFAULT_STORE_TYPE);
            String password = resolve(fqn, configKey, lookup, "/mp-rest/keyStorePassword");
            builder.keyStore(loadStore(keyStoreLocation, type, password), password);
        }

        String verifierClass = resolve(fqn, configKey, lookup, "/mp-rest/hostnameVerifier");
        if (verifierClass != null) {
            builder.hostnameVerifier(instantiateVerifier(verifierClass));
        }
    }

    private static String resolve(String fqn, String configKey,
                                  Function<String, Optional<String>> lookup, String suffix) {
        Optional<String> value = lookup.apply(fqn + suffix);
        if ((value.isEmpty() || value.get().isBlank()) && configKey != null && !configKey.isBlank()) {
            value = lookup.apply(configKey + suffix);
        }
        return value.filter(v -> !v.isBlank()).orElse(null);
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }

    static KeyStore loadStore(String location, String type, String password) {
        try (InputStream in = openLocation(location)) {
            KeyStore store = KeyStore.getInstance(type);
            store.load(in, password != null ? password.toCharArray() : null);
            return store;
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Cannot load the key store '" + location + "' (type " + type + ") — spec MP Rest Client 4.0 §5.6", e);
        }
    }

    private static InputStream openLocation(String location) throws IOException {
        if (location.startsWith("classpath:")) {
            String path = location.substring("classpath:".length());
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            ClassLoader loader = tccl != null ? tccl : CyranoSslConfigResolver.class.getClassLoader();
            InputStream in = loader.getResourceAsStream(path);
            if (in == null) {
                throw new IOException("classpath resource not found: " + path);
            }
            return in;
        }
        return URI.create(location).toURL().openStream();
    }

    private static HostnameVerifier instantiateVerifier(String className) {
        try {
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            ClassLoader loader = tccl != null ? tccl : CyranoSslConfigResolver.class.getClassLoader();
            Class<?> clazz = Class.forName(className, true, loader);
            return (HostnameVerifier) clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException(
                    "Cannot instantiate the configured hostname verifier '" + className + "'", e);
        }
    }
}
