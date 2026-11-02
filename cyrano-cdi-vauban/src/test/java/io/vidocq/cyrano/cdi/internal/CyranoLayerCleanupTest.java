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
package io.vidocq.cyrano.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vidocq.cyrano.runtime.CyranoMaintenance;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.Test;

/**
 * What Cyrano shares across clients belongs to the application layer: the container of a layer drops it when it is
 * destroyed, so a hot reload does not keep the previous layer reachable (static audit, vidocq-workspace#40 item 25).
 */
class CyranoLayerCleanupTest {

    @Test
    void theSharedCachesAreDroppedWhenTheContainerIsDestroyed() {
        int before = CyranoMaintenance.resetCount();

        try (var _ = VaubanContainer.builder().addBeanClass(CyranoLayerCleanup.class).build()) {
            assertEquals(before, CyranoMaintenance.resetCount(), "nothing is dropped while the layer runs");
        }

        assertEquals(before + 1, CyranoMaintenance.resetCount(), "the container destruction drops the caches");
    }
}
