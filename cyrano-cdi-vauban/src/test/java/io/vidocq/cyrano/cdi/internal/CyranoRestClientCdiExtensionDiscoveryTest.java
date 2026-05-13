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
 * Tests de publication de la BCE Cyrano — vérifie que l'extension est
 * découvrable via {@code META-INF/services} et, si le module est nommé, qu'il
 * l'exporte aussi via {@code provides ... with}.
 *
 * <p>Spec MicroProfile Rest Client 4.0 §6.1/§6.2 : une implémentation CDI doit
 * découvrir les interfaces {@code @RegisterRestClient} et produire un bean pour
 * chacune. La BCE doit donc être exposée de manière fiable au conteneur.</p>
 */
class CyranoRestClientCdiExtensionDiscoveryTest {

    @Test
    @DisplayName("§6.1/§6.2 — la BCE Cyrano est découvrable via ServiceLoader et publie provides quand le module est nommé")
    void build_compatible_extension_is_service_loaded_and_module_provided() {
        boolean serviceLoaderFound = ServiceLoader.load(BuildCompatibleExtension.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(CyranoRestClientCdiExtension.class::isInstance);

        assertTrue(serviceLoaderFound,
                "CyranoRestClientCdiExtension doit être découvert via META-INF/services");

        Module module = CyranoRestClientCdiExtension.class.getModule();
        ModuleDescriptor descriptor = module.getDescriptor();
        if (module.isNamed() && descriptor != null) {
            boolean providesExtension = descriptor.provides().stream()
                    .anyMatch(provides -> BuildCompatibleExtension.class.getName().equals(provides.service())
                            && provides.providers().contains(CyranoRestClientCdiExtension.class.getName()));
            assertTrue(providesExtension,
                    "Le module JPMS doit publier BuildCompatibleExtension via provides ... with");
        }
    }
}
