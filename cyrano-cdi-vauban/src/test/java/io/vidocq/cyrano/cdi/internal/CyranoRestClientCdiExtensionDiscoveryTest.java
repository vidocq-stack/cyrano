/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
