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
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer;

import java.io.Closeable;
import java.io.IOException;

/**
 * CDI disposer for synthetic REST clients — spec MP Rest Client 4.0 §8.1:
 * all proxies are {@link Closeable}. Calls {@code close()} on the proxy
 * when the CDI scope ends.
 */
public class CyranoRestClientSyntheticDisposer implements SyntheticBeanDisposer<Object> {

    @Override
    public void dispose(Object instance, Instance<Object> lookup, Parameters params) {
        if (instance instanceof Closeable c) {
            try {
                c.close();
            } catch (IOException ignored) {
                // close() ne lance pas d'IOException en pratique
            }
        }
    }
}
