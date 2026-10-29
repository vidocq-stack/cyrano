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
package io.vidocq.cyrano.internal.gen;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client generated at run time is the exception: it costs startup time and has no ahead-of-time equivalent. Each
 * interface served that way is announced once, by a framed warning that names it and says how to fix the build.
 */
class RuntimeGenerationTest {

    @Path("/plain")
    public interface PlainApi {
        @GET
        String get();
    }

    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @BeforeEach
    void capture() {
        ClientProxyRegistry.resetForTests();
        RuntimeGeneration.redirectForTests(warnings::add);
    }

    @AfterEach
    void restore() {
        RuntimeGeneration.redirectForTests(null);
        ClientProxyRegistry.resetForTests();
    }

    @Test
    void aClientGeneratedAtRunTimeIsAnnouncedOnce() {
        ClientProxyRegistry.resolve(PlainApi.class);
        ClientProxyRegistry.resetForTests();
        ClientProxyRegistry.resolve(PlainApi.class);

        assertEquals(1, warnings.size());
        String warning = warnings.getFirst();
        assertTrue(warning.contains("CYRANO — RUNTIME CODE GENERATION FALLBACK"), warning);
        assertTrue(warning.contains(PlainApi.class.getName()), warning);
        assertTrue(warning.contains("cyrano-processor"), warning);
    }

    @Test
    void aGeneratedClientIsNotAnnounced() {
        ClientProxyRegistry.resolve(FixtureApi.class);

        assertEquals(List.of(), warnings);
    }
}
