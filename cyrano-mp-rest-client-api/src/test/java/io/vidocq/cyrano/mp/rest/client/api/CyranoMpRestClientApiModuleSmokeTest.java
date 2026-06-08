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
package io.vidocq.cyrano.mp.rest.client.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CyranoMpRestClientApiModuleSmokeTest {

    @Test
    void repackaged_module_descriptor_is_explicit_and_exports_mp_rest_client_api_packages() {
        Path jar = repackagedJar();

        ModuleDescriptor descriptor = ModuleFinder.of(jar)
                .find("io.vidocq.cyrano.mp.rest.client.api")
                .orElseThrow(() -> new AssertionError("Module io.vidocq.cyrano.mp.rest.client.api not found in " + jar))
                .descriptor();

        assertNotNull(descriptor.rawVersion().orElse(null));
        assertFalse(descriptor.isAutomatic(), "The repackaged module must not be automatic");
        assertEquals("io.vidocq.cyrano.mp.rest.client.api", descriptor.name());

        Set<String> exportedPackages = descriptor.exports().stream()
                .map(ModuleDescriptor.Exports::source)
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(exportedPackages.contains("org.eclipse.microprofile.rest.client"));
        assertTrue(exportedPackages.contains("org.eclipse.microprofile.rest.client.annotation"));
        assertTrue(exportedPackages.contains("org.eclipse.microprofile.rest.client.ext"));
        assertTrue(exportedPackages.contains("org.eclipse.microprofile.rest.client.inject"));
        assertTrue(exportedPackages.contains("org.eclipse.microprofile.rest.client.spi"));
    }

    /**
     * Resolve the repackaged jar by glob rather than a hardcoded version. The cross-repo
     * downstream rebuild ({@code ci/build-impacted}) runs {@code versions:set}, so the jar
     * carries a PR version (e.g. {@code cyrano-mp-rest-client-api-0.1.0-PR4.<sha>.jar}), not
     * {@code -0.1.0-SNAPSHOT}. The jar is produced at the {@code process-classes} phase
     * (maven-jar-plugin), so it already exists when this test runs.
     */
    private static Path repackagedJar() {
        Path target = Path.of("target");
        try (var files = Files.list(target)) {
            return files
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("cyrano-mp-rest-client-api-") && name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar")
                                && !name.endsWith("-tests.jar");
                    })
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Repackaged jar cyrano-mp-rest-client-api-*.jar not found in " + target.toAbsolutePath()));
        } catch (IOException e) {
            throw new AssertionError("Unable to list " + target.toAbsolutePath(), e);
        }
    }
}

