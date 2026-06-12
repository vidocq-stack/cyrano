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
package io.vidocq.cyrano.internal.gen;

import io.vidocq.cyrano.internal.CyranoInvocationHandler;
import io.vidocq.cyrano.internal.CyranoProxyCache;
import io.vidocq.cyrano.internal.CyranoProxyGenerator;
import io.vidocq.cyrano.internal.RequestSpec;
import io.vidocq.cyrano.spi.gen.ClientProxyFactory;

import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Client proxy resolution chain — generated artifacts first, runtime generation
 * strictly as fallback (workspace codegen rule, audit CG-01). Mirrors cassini's
 * {@code AdapterRegistry} pattern, hit counters included:
 *
 * <ol>
 *   <li><strong>ServiceLoader</strong> of {@link ClientProxyFactory} — the
 *       JPMS-friendly path: a strict module declares
 *       {@code provides ClientProxyFactory with com.acme.MyApi$$CyranoClient$Factory}
 *       and keeps the client package fully encapsulated.</li>
 *   <li><strong>Naming convention</strong> —
 *       {@code Class.forName(iface.getName() + "$$CyranoClient")} then its nested
 *       {@code Factory}. Works on the classpath and for exported packages.</li>
 *   <li><strong>Runtime fallback</strong> — {@link CyranoProxyGenerator}
 *       (Class-File API) + {@code CyranoInterfaceScanner}, for interfaces compiled
 *       without the Cyrano annotation processor (e.g. pre-compiled TCK jars).</li>
 * </ol>
 *
 * <p>Resolution is cached per interface; counters count resolutions, not cache
 * hits, so tests can assert which tier actually did the work.</p>
 */
public final class ClientProxyRegistry {

    private static final System.Logger LOG = System.getLogger(ClientProxyRegistry.class.getName());

    /** Which tier produced the proxy class. */
    public enum Source { SERVICE_LOADER, PRE_GENERATED, RUNTIME_GENERATED }

    /**
     * Outcome of a resolution: the request specs (descriptor order == proxy method
     * indices) and an instantiator binding a handler to a fresh proxy instance.
     */
    public record Resolved(Source source,
                           List<RequestSpec> specs,
                           Function<CyranoInvocationHandler, Object> instantiator) {
        public Resolved {
            specs = List.copyOf(specs);
        }
    }

    private static final ConcurrentHashMap<Class<?>, Resolved> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger SERVICE_LOADER_HITS = new AtomicInteger();
    private static final AtomicInteger PRE_GENERATED_HITS = new AtomicInteger();
    private static final AtomicInteger RUNTIME_GENERATED_HITS = new AtomicInteger();

    private ClientProxyRegistry() {
        // utility
    }

    public static Resolved resolve(Class<?> iface) {
        return CACHE.computeIfAbsent(iface, ClientProxyRegistry::doResolve);
    }

    private static Resolved doResolve(Class<?> iface) {
        Resolved fromLoader = tryServiceLoader(iface);
        if (fromLoader != null) {
            SERVICE_LOADER_HITS.incrementAndGet();
            return fromLoader;
        }
        Resolved fromConvention = tryNamingConvention(iface);
        if (fromConvention != null) {
            PRE_GENERATED_HITS.incrementAndGet();
            return fromConvention;
        }
        RUNTIME_GENERATED_HITS.incrementAndGet();
        return runtimeFallback(iface);
    }

    private static Resolved tryServiceLoader(Class<?> iface) {
        // Module path first: a strict-JPMS user module declares its factory via
        // `provides ClientProxyFactory with ...` only — that declaration is honoured
        // by the layer overload, NOT by ServiceLoader.load(type, classLoader) which
        // reads META-INF/services files alone (the processor emits one for the
        // classpath case, but a hand-written module-info must work too).
        ModuleLayer layer = iface.getModule().getLayer();
        if (layer != null) {
            try {
                for (ClientProxyFactory factory : ServiceLoader.load(layer, ClientProxyFactory.class)) {
                    if (factory.clientInterface() == iface) {
                        return fromFactory(Source.SERVICE_LOADER, factory);
                    }
                }
            } catch (java.util.ServiceConfigurationError e) {
                LOG.log(System.Logger.Level.DEBUG,
                        () -> "ClientProxyFactory layer scan failed: " + e);
            }
        }
        for (ClassLoader loader : candidateLoaders(iface)) {
            try {
                for (ClientProxyFactory factory : ServiceLoader.load(ClientProxyFactory.class, loader)) {
                    if (factory.clientInterface() == iface) {
                        return fromFactory(Source.SERVICE_LOADER, factory);
                    }
                }
            } catch (java.util.ServiceConfigurationError e) {
                LOG.log(System.Logger.Level.DEBUG,
                        () -> "ClientProxyFactory ServiceLoader scan failed on " + loader + ": " + e);
            }
        }
        return null;
    }

    private static Resolved tryNamingConvention(Class<?> iface) {
        String generatedName = iface.getName() + "$$CyranoClient";
        Class<?> generated;
        try {
            generated = Class.forName(generatedName, false, iface.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
        try {
            Class<?> factoryClass = Class.forName(generatedName + "$Factory", true, iface.getClassLoader());
            ClientProxyFactory factory =
                    (ClientProxyFactory) factoryClass.getDeclaredConstructor().newInstance();
            if (factory.clientInterface() != iface) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> generatedName + " targets " + factory.clientInterface()
                                + " instead of " + iface + " — stale jar? Using the runtime fallback.");
                return null;
            }
            return fromFactory(Source.PRE_GENERATED, factory);
        } catch (ReflectiveOperationException | ClassCastException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "Broken generated client " + generated.getName()
                            + " — falling back to runtime generation: " + e);
            return null;
        }
    }

    private static Resolved fromFactory(Source source, ClientProxyFactory factory) {
        List<RequestSpec> specs = DescriptorConverter.convert(factory.descriptor());
        return new Resolved(source, specs, factory::newProxy);
    }

    private static Resolved runtimeFallback(Class<?> iface) {
        CyranoProxyCache.Entry entry = CyranoProxyCache.getOrGenerate(iface);
        return new Resolved(Source.RUNTIME_GENERATED, entry.specs(),
                handler -> CyranoProxyGenerator.instantiate(entry.proxyClass(), handler));
    }

    private static List<ClassLoader> candidateLoaders(Class<?> iface) {
        var loaders = new java.util.LinkedHashSet<ClassLoader>();
        if (iface.getClassLoader() != null) loaders.add(iface.getClassLoader());
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) loaders.add(tccl);
        ClassLoader self = ClientProxyRegistry.class.getClassLoader();
        if (self != null) loaders.add(self);
        return List.copyOf(loaders);
    }

    // --- observability (mirrors cassini AdapterRegistry) -------------------

    /** Resolutions served by the ServiceLoader tier. */
    public static int serviceLoaderHits() {
        return SERVICE_LOADER_HITS.get();
    }

    /** Resolutions served by the pre-generated naming-convention tier. */
    public static int preGeneratedHits() {
        return PRE_GENERATED_HITS.get();
    }

    /** Resolutions that fell back to the runtime Class-File generator. */
    public static int runtimeGeneratedHits() {
        return RUNTIME_GENERATED_HITS.get();
    }

    /** Clears cache and counters — test isolation only. */
    public static void resetForTests() {
        CACHE.clear();
        SERVICE_LOADER_HITS.set(0);
        PRE_GENERATED_HITS.set(0);
        RUNTIME_GENERATED_HITS.set(0);
    }
}
