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

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CyranoMpRestClientApiModuleSmokeTest {

    @Test
    void repackaged_module_descriptor_is_explicit_and_exports_mp_rest_client_api_packages() {
        Path jar = Path.of("target", "cyrano-mp-rest-client-api-0.1.0-SNAPSHOT.jar");

        ModuleDescriptor descriptor = ModuleFinder.of(jar)
                .find("io.vidocq.cyrano.mp.rest.client.api")
                .orElseThrow(() -> new AssertionError("Module io.vidocq.cyrano.mp.rest.client.api introuvable"))
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
}

