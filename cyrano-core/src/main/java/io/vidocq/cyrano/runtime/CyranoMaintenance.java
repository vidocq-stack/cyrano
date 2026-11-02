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

import io.vidocq.cyrano.internal.JsonbHolder;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maintenance entry point for hosts that run Cyrano across application reloads in the same JVM.
 *
 * <p>The Vidocq dev mode boots a fresh application layer (a new class loader) on every hot reload while the Cyrano
 * library stays in the parent layer. What Cyrano caches <em>per client interface</em> (the generated proxies) is
 * attached to the interface itself and goes away with its layer. The one shared {@link jakarta.json.bind.Jsonb}
 * is not: it holds the entity classes it has seen. A host calls {@link #resetCaches()} when the layer stops, and the
 * next use builds a {@code Jsonb} for the layer that follows. The Vauban integration does it when the container
 * is destroyed.</p>
 */
public final class CyranoMaintenance {

    private static final AtomicInteger RESETS = new AtomicInteger();

    private CyranoMaintenance() {
    }

    /** Drops what is shared across clients and holds classes of the application layer. */
    public static void resetCaches() {
        JsonbHolder.reset();
        RESETS.incrementAndGet();
    }

    /** How many times {@link #resetCaches()} ran (observability, as the resolution counters of the proxy registry). */
    public static int resetCount() {
        return RESETS.get();
    }
}
