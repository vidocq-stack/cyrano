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

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Lazy + thread-safe holder of a {@link Jsonb} instance shared by all Cyrano
 * invocations. {@code JsonbBuilder.create()} loads the implementation via
 * {@link java.util.ServiceLoader ServiceLoader} — champollion at runtime.
 *
 * <p>No {@code synchronized}: a benign race may build two instances on the first use, one wins. The instance
 * belongs to the application layer that used it (it holds the entity classes it has seen): the host drops it with
 * {@link io.vidocq.cyrano.runtime.CyranoMaintenance#resetCaches()} when the layer stops.</p>
 *
 * <p>Public (but in the unexported {@code internal} package) so that the SSE
 * support in {@code io.vidocq.cyrano.internal.sse} can share the instance.</p>
 */
public final class JsonbHolder {

    private static final AtomicReference<Jsonb> INSTANCE = new AtomicReference<>();

    private JsonbHolder() {}

    public static Jsonb get() {
        Jsonb jsonb = INSTANCE.get();
        if (jsonb != null) {
            return jsonb;
        }
        INSTANCE.compareAndSet(null, JsonbBuilder.create());
        return INSTANCE.get();
    }

    /** Forgets the shared instance: the next {@link #get()} builds one for the application layer then current. */
    public static void reset() {
        INSTANCE.set(null);
    }
}

