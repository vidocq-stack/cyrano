/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
                .orElseThrow(() -> new AssertionError("Module io.vidocq.cyrano.mp.rest.client.api introuvable dans " + jar))
                .descriptor();

        assertNotNull(descriptor.rawVersion().orElse(null));
        assertFalse(descriptor.isAutomatic(), "Le module repacke ne doit pas etre automatique");
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
                            "Repackaged jar cyrano-mp-rest-client-api-*.jar introuvable dans " + target.toAbsolutePath()));
        } catch (IOException e) {
            throw new AssertionError("Impossible de lister " + target.toAbsolutePath(), e);
        }
    }
}

