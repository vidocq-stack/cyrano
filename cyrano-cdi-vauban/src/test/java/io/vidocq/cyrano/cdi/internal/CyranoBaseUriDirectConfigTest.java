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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** MicroProfile Config is read directly when its API is present; the reflective lookup stays as the fallback. */
class CyranoBaseUriDirectConfigTest {

    @Test
    void theConfigurationIsReadWithoutReflectionWhenTheApiIsPresent() {
        assertTrue(CyranoBaseUriResolver.readsMpConfigDirectly());
    }

    @Test
    void bothLookupsFallBackToTheSystemProperty() {
        String key = "cyrano.test.direct." + System.nanoTime();
        System.setProperty(key, "v");
        try {
            assertEquals(Optional.of("v"), CyranoBaseUriResolver.defaultMpConfigLookup().apply(key));
            assertEquals(Optional.of("v"), CyranoBaseUriResolver.reflectiveMpConfigLookup().apply(key));
        } finally {
            System.clearProperty(key);
        }
    }
}
