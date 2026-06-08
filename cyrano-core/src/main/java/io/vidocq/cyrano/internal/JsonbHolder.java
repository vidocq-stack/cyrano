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

/**
 * Lazy + thread-safe holder of a {@link Jsonb} instance shared by all Cyrano
 * invocations. {@code JsonbBuilder.create()} loads the implementation via
 * {@link java.util.ServiceLoader ServiceLoader} — champollion at runtime.
 *
 * <p>No {@code synchronized}: initialization via the holder pattern (lazy class
 * init is guaranteed thread-safe by the JVM).</p>
 */
final class JsonbHolder {

    private JsonbHolder() {}

    static Jsonb get() {
        return Lazy.INSTANCE;
    }

    private static final class Lazy {
        static final Jsonb INSTANCE = JsonbBuilder.create();
    }
}

