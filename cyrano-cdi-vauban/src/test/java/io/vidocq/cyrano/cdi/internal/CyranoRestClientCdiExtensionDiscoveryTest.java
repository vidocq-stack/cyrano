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

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cyrano BCE publication tests — verifies that the extension is declared in
 * {@code META-INF/services} (class-path deployments) and, if the module is named, is
 * also published via {@code provides ... with} (module-path deployments).
 *
 * <p>Spec MicroProfile Rest Client 4.0 §6.1/§6.2: a CDI implementation must
 * discover {@code @RegisterRestClient} interfaces and produce a bean for
 * each one. The BCE must therefore be reliably exposed to the container.</p>
 *
 * <p>The test reads both declarations rather than calling {@link java.util.ServiceLoader}: on the
 * module path only a module that {@code uses} the service may look it up — the container does
 * (vauban-core), this module does not. {@link CyranoRestClientCdiIntegrationTest} covers the actual
 * lookup by booting the container.</p>
 */
class CyranoRestClientCdiExtensionDiscoveryTest {

    @Test
    @DisplayName("§6.1/§6.2 — Cyrano BCE is declared in META-INF/services and published via provides when the module is named")
    void build_compatible_extension_is_service_declared_and_module_provided() throws IOException {
        String servicesFile = "/META-INF/services/" + BuildCompatibleExtension.class.getName();
        try (InputStream in = CyranoRestClientCdiExtension.class.getResourceAsStream(servicesFile)) {
            assertNotNull(in, servicesFile + " must exist");
            boolean declared = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .anyMatch(CyranoRestClientCdiExtension.class.getName()::equals);
            assertTrue(declared, "CyranoRestClientCdiExtension must be declared in " + servicesFile);
        }

        Module module = CyranoRestClientCdiExtension.class.getModule();
        ModuleDescriptor descriptor = module.getDescriptor();
        if (module.isNamed() && descriptor != null) {
            boolean providesExtension = descriptor.provides().stream()
                    .anyMatch(provides -> BuildCompatibleExtension.class.getName().equals(provides.service())
                            && provides.providers().contains(CyranoRestClientCdiExtension.class.getName()));
            assertTrue(providesExtension,
                    "The Java module must publish BuildCompatibleExtension via provides ... with");
        }
    }
}
