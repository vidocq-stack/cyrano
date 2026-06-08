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
package io.vidocq.cyrano.runtime;

import io.vidocq.cyrano.internal.CyranoRestClientBuilder;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.spi.RestClientBuilderResolver;

/**
 * Implementation of SPI {@link RestClientBuilderResolver} — invoked by
 * {@link RestClientBuilder#newBuilder()} via {@link java.util.ServiceLoader ServiceLoader}.
 *
 * <p>Spec MicroProfile Rest Client 4.0 §10 (SPI): this resolver is discovered via
 * {@code META-INF/services/org.eclipse.microprofile.rest.client.spi.RestClientBuilderResolver}.
 * Only one instance is installed per classloader.</p>
 *
 * <p>Must expose a public no-arg constructor — {@link java.util.ServiceLoader} requirement.</p>
 */
public final class CyranoRestClientBuilderResolver extends RestClientBuilderResolver {

    public CyranoRestClientBuilderResolver() {
        // required for ServiceLoader
    }

    @Override
    public RestClientBuilder newBuilder() {
        var builder = new CyranoRestClientBuilder();
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        Iterable<org.eclipse.microprofile.rest.client.spi.RestClientBuilderListener> listeners =
                tccl != null
                        ? java.util.ServiceLoader.load(org.eclipse.microprofile.rest.client.spi.RestClientBuilderListener.class, tccl)
                        : java.util.ServiceLoader.load(org.eclipse.microprofile.rest.client.spi.RestClientBuilderListener.class);
        //Spec MP Rest Client 4.0 §10.1 — RestClientBuilderListener.onNewBuilder() is invoked
        //for each new builder created via RestClientBuilder.newBuilder().
        for (var listener : listeners) {
            try {
                listener.onNewBuilder(builder);
            } catch (RuntimeException ignored) {
                //a failed lister must not block the creation of the builder
            }
        }
        return builder;
    }
}

