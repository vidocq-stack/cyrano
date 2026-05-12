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
 * Cache de proxies générés — un seul {@code Cyrano$<Interface>} par interface client,
 * partagé entre toutes les invocations de {@code RestClientBuilder.build()}.
 *
 * <p>Thread-safe via {@link ConcurrentHashMap#computeIfAbsent} — virtual-thread-friendly,
 * pas de {@code synchronized}.</p>
 */
public final class CyranoProxyCache {

    private static final ConcurrentHashMap<Class<?>, Entry> CACHE = new ConcurrentHashMap<>();

    private CyranoProxyCache() {
        // utility
    }

    /**
     * Récupère (ou génère à la demande) la classe proxy + les specs associées pour {@code iface}.
     *
     * @param iface interface client annotée selon MicroProfile Rest Client 4.0 §3
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
     * Paire {@code (proxyClass, requestSpecs)} — la liste des specs est dans le même ordre
     * d'itération que celui utilisé par {@link CyranoProxyGenerator} pour indexer les
     * méthodes (ordre d'insertion {@link java.util.LinkedHashMap LinkedHashMap}).
     */
    public record Entry(Class<?> proxyClass, java.util.List<RequestSpec> specs) {}
}

