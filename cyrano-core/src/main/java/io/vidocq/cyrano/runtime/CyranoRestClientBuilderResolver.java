/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cyrano.runtime;

import io.vidocq.cyrano.internal.CyranoRestClientBuilder;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.spi.RestClientBuilderResolver;

/**
 * Implémentation du SPI {@link RestClientBuilderResolver} — invoqué par
 * {@link RestClientBuilder#newBuilder()} via {@link java.util.ServiceLoader ServiceLoader}.
 *
 * <p>Spec MicroProfile Rest Client 4.0 §10 (SPI) : ce resolver est découvert via
 * {@code META-INF/services/org.eclipse.microprofile.rest.client.spi.RestClientBuilderResolver}.
 * Une seule instance est instanciée par classloader.</p>
 *
 * <p>Doit être un constructeur sans argument public — exigence ServiceLoader.</p>
 */
public final class CyranoRestClientBuilderResolver extends RestClientBuilderResolver {

    public CyranoRestClientBuilderResolver() {
        // requis pour ServiceLoader
    }

    @Override
    public RestClientBuilder newBuilder() {
        var builder = new CyranoRestClientBuilder();
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        Iterable<org.eclipse.microprofile.rest.client.spi.RestClientBuilderListener> listeners =
                tccl != null
                        ? java.util.ServiceLoader.load(org.eclipse.microprofile.rest.client.spi.RestClientBuilderListener.class, tccl)
                        : java.util.ServiceLoader.load(org.eclipse.microprofile.rest.client.spi.RestClientBuilderListener.class);
        // Spec MP Rest Client 4.0 §10.1 — RestClientBuilderListener.onNewBuilder() est invoqué
        // pour chaque nouveau builder créé via RestClientBuilder.newBuilder().
        for (var listener : listeners) {
            try {
                listener.onNewBuilder(builder);
            } catch (RuntimeException ignored) {
                // un listener défaillant ne doit pas bloquer la création du builder
            }
        }
        return builder;
    }
}

