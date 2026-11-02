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

import io.vidocq.cyrano.runtime.CyranoMaintenance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.event.Observes;

/**
 * Ties what Cyrano shares across clients to the lifetime of the container that hosts it.
 *
 * <p>The Vidocq dev mode boots a fresh application layer, with a new container, on every hot reload, while the
 * Cyrano library stays in the parent layer. The one shared {@code Jsonb} holds the entity classes it has seen: it is
 * dropped when the container of its layer is destroyed, so the layer can be collected and the next use builds a
 * {@code Jsonb} for the layer that follows ({@link CyranoMaintenance#resetCaches()}). Under another CDI container
 * the same event is fired at undeployment, which is harmless.</p>
 */
@ApplicationScoped
public class CyranoLayerCleanup {

    void onContainerDestroyed(@Observes @BeforeDestroyed(ApplicationScoped.class) Object event) {
        CyranoMaintenance.resetCaches();
    }
}
