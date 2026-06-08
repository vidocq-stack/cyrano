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

import java.lang.reflect.Method;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;

/**
 * Provider instantiation SPI — allows an adapter (e.g. {@code cyrano-cdi-vauban})
 * to supply CDI-managed instances instead of reflective ones.
 *
 * <p>Spec MP Rest Client 4.0 §4.2.4: <em>« If using CDI, RestClient implementations must
 * use the BeanManager to obtain providers if they are managed beans. »</em></p>
 *
 * <p>Discovered via {@link ServiceLoader}: one active {@code ProviderInstantiator}
 * (the first found wins). If none is declared, {@link #defaultInstantiator()}
 * falls back to reflection on the no-arg constructor.</p>
 *
 * <p>{@link #create(Class)} <strong>must</strong> return {@code null} if the class
 * is not a known managed bean — the {@code CyranoClientConfiguration} will then
 * fall back to standard reflective instantiation.</p>
 *
 * @since 0.1.0 (M4-3)
 */
@FunctionalInterface
public interface ProviderInstantiator {

    /**
     * Attempt to create an instance of {@code componentClass} via the container.
     *
     * @param componentClass class of provider to instantiate (filter, interceptor, mapper...)
     * @return managed instance, or {@code null} if the container has no bean for this class
     */
    Object create(Class<?> componentClass);

    /**
     * Optional hook to surround a client method invocation (e.g. CDI interception).
     * The default implementation is transparent.
     */
    default Object aroundInvoke(Object target, Method method, Object[] args, Callable<Object> invocation) throws Exception {
        return invocation.call();
    }

    /**
     * Instantiator " plain Java" — invokes {@link Class#getDeclaredConstructor()}.
     * Used in pure SE mode (without CDI).
     */
    static ProviderInstantiator defaultInstantiator() {
        return cls -> {
            try {
                var ctor = cls.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Provider is not instantiable: " + cls, e);
            }
        };
    }

    /**
     * Loads the current implementation via {@link ServiceLoader}, or returns the default
     * reflective one. Result is recomputed per call; no thread-safe cache is imposed here.
     */
    static ProviderInstantiator current() {
        for (ProviderInstantiator pi : ServiceLoader.load(ProviderInstantiator.class)) {
            return pi;
        }
        return defaultInstantiator();
    }
}

