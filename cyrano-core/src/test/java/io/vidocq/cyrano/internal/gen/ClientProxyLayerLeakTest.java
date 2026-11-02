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

import io.vidocq.cyrano.internal.ProviderModule;
import io.vidocq.cyrano.spi.gen.ClientProxyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.ref.WeakReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The proxy caches must not outlive the application layer that owns the client interface: the Vidocq dev mode
 * boots a fresh layer on every hot reload, in the same JVM, and a JVM-wide table keyed by the interface
 * {@code Class} kept every discarded layer reachable (static audit, vidocq-workspace#40 item 25).
 */
class ClientProxyLayerLeakTest {

    @BeforeEach
    void reset() {
        ClientProxyRegistry.resetForTests();
    }

    /** The generated tier: the factory is found through the layer, its resolution cached for the interface. */
    @Test
    void aDiscardedLayerIsCollectableAfterAGeneratedClientWasResolved(@TempDir java.nio.file.Path dir) throws Exception {
        WeakReference<ClassLoader> loader = resolveInADiscardedLayer(dir, true);

        assertNull(collected(loader), "the layer of a resolved generated client stays reachable");
        assertEquals(1, ClientProxyRegistry.serviceLoaderHits());
    }

    /** The runtime tier: the proxy class is generated, and both caches (registry and proxy cache) are filled. */
    @Test
    void aDiscardedLayerIsCollectableAfterARuntimeGeneratedClientWasResolved(@TempDir java.nio.file.Path dir)
            throws Exception {
        WeakReference<ClassLoader> loader = resolveInADiscardedLayer(dir, false);

        assertNull(collected(loader), "the layer of a runtime-generated client stays reachable");
        assertEquals(1, ClientProxyRegistry.runtimeGeneratedHits());
    }

    /** A cached resolution is still served from the cache while the layer lives. */
    @Test
    void aResolutionIsStillCachedPerInterface(@TempDir java.nio.file.Path dir) throws Exception {
        ModuleLayer layer = layer(dir, true);
        Class<?> api = layer.findLoader("cyrano.test.client").loadClass(LoaderApi.class.getName());

        var first = ClientProxyRegistry.resolve(api);
        var second = ClientProxyRegistry.resolve(api);

        assertEquals(first, second);
        assertEquals(1, ClientProxyRegistry.serviceLoaderHits());
    }

    private static WeakReference<ClassLoader> resolveInADiscardedLayer(java.nio.file.Path dir, boolean generated)
            throws Exception {
        ModuleLayer layer = layer(dir, generated);
        ClassLoader loader = layer.findLoader("cyrano.test.client");
        Class<?> api = loader.loadClass(LoaderApi.class.getName());
        ClientProxyRegistry.resolve(api);
        return new WeakReference<>(loader);
    }

    private static ModuleLayer layer(java.nio.file.Path dir, boolean generated) {
        ProviderModule module = ProviderModule.named(dir, "cyrano.test.client").with(LoaderApi.class);
        if (generated) {
            module = module.with(LoaderApi$$CyranoClient.class)
                    .provides(ClientProxyFactory.class, LoaderApi$$CyranoClient.Factory.class);
        }
        return module.layer();
    }

    /** {@code null} once the loader is collected, the loader itself while something still holds it. */
    private static ClassLoader collected(WeakReference<ClassLoader> loader) {
        for (int i = 0; i < 40 && loader.get() != null; i++) {
            System.gc();
            LockSupport.parkNanos(25_000_000L);
        }
        return loader.get();
    }
}
