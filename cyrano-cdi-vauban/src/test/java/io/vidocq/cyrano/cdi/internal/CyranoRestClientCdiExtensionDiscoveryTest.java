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

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cyrano BCE publication tests — verifies that the extension is
 * discoverable via {@code META-INF/services} and, if the module is named, is
 * also exported via {@code provides ... with}.
 *
 * <p>Spec MicroProfile Rest Client 4.0 §6.1/§6.2: a CDI implementation must
 * discover {@code @RegisterRestClient} interfaces and produce a bean for
 * each one. The BCE must therefore be reliably exposed to the container.</p>
 */
class CyranoRestClientCdiExtensionDiscoveryTest {

    @Test
    @DisplayName("§6.1/§6.2 — Cyrano BCE is discoverable via ServiceLoader and publishes provides when the module is named")
    void build_compatible_extension_is_service_loaded_and_module_provided() {
        boolean serviceLoaderFound = ServiceLoader.load(BuildCompatibleExtension.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(CyranoRestClientCdiExtension.class::isInstance);

        assertTrue(serviceLoaderFound,
                "CyranoRestClientCdiExtension must be discovered via META-INF/services");

        Module module = CyranoRestClientCdiExtension.class.getModule();
        ModuleDescriptor descriptor = module.getDescriptor();
        if (module.isNamed() && descriptor != null) {
            boolean providesExtension = descriptor.provides().stream()
                    .anyMatch(provides -> BuildCompatibleExtension.class.getName().equals(provides.service())
                            && provides.providers().contains(CyranoRestClientCdiExtension.class.getName()));
            assertTrue(providesExtension,
                    "The JPMS module must publish BuildCompatibleExtension via provides ... with");
        }
    }
}
