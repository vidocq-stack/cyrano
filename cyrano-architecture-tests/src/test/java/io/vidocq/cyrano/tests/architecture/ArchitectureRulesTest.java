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
package io.vidocq.cyrano.tests.architecture;

import static io.vidocq.cyrano.tests.architecture.CyranoClasses.SHIPPED;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;

import java.util.stream.Stream;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The architecture rules on the classes Cyrano ships: cyrano-api, cyrano-core, cyrano-cdi-vauban and cyrano-processor,
 * test classes left out (Vidocq/vidocq#289). A failure names each class and the dependency or call that breaks the
 * rule.
 */
class ArchitectureRulesTest {

    @Test
    void theShippedClassesAreFound() {
        // Guards the other tests: a class path change that imports nothing would make every rule pass.
        assertTrue(
                SHIPPED.contain("io.vidocq.cyrano.spi.gen.ClientProxyFactory"),
                "cyrano-api imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.cyrano.internal.CyranoInterfaceScanner"),
                "cyrano-core imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.cyrano.runtime.ProviderInstantiator"),
                "cyrano-core runtime imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.cyrano.cdi.internal.CyranoBaseUriResolver"),
                "cyrano-cdi-vauban imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.cyrano.processor.CyranoClientProcessor"),
                "cyrano-processor imported");
    }

    static Stream<Named<ArchRule>> rules() {
        return Stream.of(
                named("API_DEPENDENCIES", ArchitectureRules.API_DEPENDENCIES),
                named("CORE_DEPENDENCIES", ArchitectureRules.CORE_DEPENDENCIES),
                named("CDI_VAUBAN_DEPENDENCIES", ArchitectureRules.CDI_VAUBAN_DEPENDENCIES),
                named("PROCESSOR_DEPENDENCIES", ArchitectureRules.PROCESSOR_DEPENDENCIES),
                named("LAYERS", ArchitectureRules.LAYERS),
                named("NO_REFLECTION", ArchitectureRules.NO_REFLECTION),
                named("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS),
                named("NO_THREAD_LOCAL", ArchitectureRules.NO_THREAD_LOCAL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void theShippedClassesKeepTheRule(ArchRule rule) {
        rule.check(SHIPPED);
    }
}
