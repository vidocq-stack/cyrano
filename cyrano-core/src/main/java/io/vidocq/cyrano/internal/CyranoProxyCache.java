/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cyrano.internal;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache of generated proxies — one {@code Cyrano$<Interface>} per client interface,
 * shared between all invocations of {@code RestClientBuilder.build()}.
 *
 * <p>Thread-safe via {@link ConcurrentHashMap#computeIfAbsent} — virtual-thread-friendly,
 * with no {@code synchronized}.</p>
 */
public final class CyranoProxyCache {

    private static final ConcurrentHashMap<Class<?>, Entry> CACHE = new ConcurrentHashMap<>();

    private CyranoProxyCache() {
        // utility
    }

    /**
     * Recovers (or generates on demand) the proxy class + specs associated with {@code iface}.
     *
     * @param iface client interface annotated according to MicroProfile Rest Client 4.0 §3
     */
    public static Entry getOrGenerate(Class<?> iface) {
        return CACHE.computeIfAbsent(iface, CyranoProxyCache::build);
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

