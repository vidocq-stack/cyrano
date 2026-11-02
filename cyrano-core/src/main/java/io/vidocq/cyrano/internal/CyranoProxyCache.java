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
package io.vidocq.cyrano.internal;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Cache of generated proxies — one {@code Cyrano$<Interface>} per client interface,
 * shared between all invocations of {@code RestClientBuilder.build()}.
 *
 * <p>The cache is a {@link ClassValue}: an entry lives and dies with the interface it describes, so the
 * application layer a hot reload discards (a fresh class loader, in the same JVM) is not kept reachable by a
 * JVM-wide table keyed by its classes. Thread-safe and virtual-thread-friendly, with no {@code synchronized}.</p>
 */
public final class CyranoProxyCache {

    private static final ClassValue<Entry> CACHE = new ClassValue<>() {
        @Override
        protected Entry computeValue(Class<?> iface) {
            return build(iface);
        }
    };

    private CyranoProxyCache() {
        // utility
    }

    /**
     * Recovers (or generates on demand) the proxy class + specs associated with {@code iface}.
     *
     * @param iface client interface annotated according to MicroProfile Rest Client 4.0 §3
     */
    public static Entry getOrGenerate(Class<?> iface) {
        return CACHE.get(iface);
    }

    private static Entry build(Class<?> iface) {
        Map<Method, RequestSpec> scanned = CyranoInterfaceScanner.scan(iface);
        Class<?> proxyClass = CyranoProxyGenerator.generate(iface, scanned);
        return new Entry(proxyClass, java.util.List.copyOf(scanned.values()));
    }

    /**
     * Pair {@code (proxyClass, requestSpecs)} — the list of specs is in the same order
     * the one used by {@link CyranoProxyGenerator} to index the
     * methods (insertion order {@link java.util.LinkedHashMap LinkedHashMap}).
     */
    public record Entry(Class<?> proxyClass, java.util.List<RequestSpec> specs) {}
}

