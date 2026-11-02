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

import io.vidocq.cyrano.runtime.CyranoMaintenance;
import jakarta.json.bind.Jsonb;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The {@link Jsonb} the client shares holds the entity classes it has seen, so it belongs to the application layer
 * that used it: the host drops it when the layer stops ({@link CyranoMaintenance#resetCaches()}) and the next use
 * builds one for the layer that follows. Left in a static for the life of the JVM it kept the first layer of a
 * {@code vidocq dev} session reachable after every hot reload (static audit, vidocq-workspace#40 item 25).
 */
class JsonbHolderResetTest {

    @Test
    void theJsonbIsSharedUntilTheLayerStops() {
        assertSame(JsonbHolder.get(), JsonbHolder.get());
    }

    @Test
    void theNextUseAfterAResetBuildsANewJsonb() {
        Jsonb before = JsonbHolder.get();

        CyranoMaintenance.resetCaches();

        assertNotSame(before, JsonbHolder.get());
    }

    @Test
    void theDroppedJsonbIsCollectable() {
        WeakReference<Jsonb> dropped = new WeakReference<>(JsonbHolder.get());

        CyranoMaintenance.resetCaches();
        JsonbHolder.get();

        for (int i = 0; i < 40 && dropped.get() != null; i++) {
            System.gc();
            LockSupport.parkNanos(25_000_000L);
        }
        assertNull(dropped.get(), "the Jsonb of the previous layer stays reachable");
    }

    @Test
    void aResetIsCounted() {
        int before = CyranoMaintenance.resetCount();

        CyranoMaintenance.resetCaches();

        assertEquals(before + 1, CyranoMaintenance.resetCount());
    }
}
