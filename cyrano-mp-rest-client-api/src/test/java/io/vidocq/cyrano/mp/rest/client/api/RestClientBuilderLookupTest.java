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

import io.vidocq.cyrano.mp.rest.client.api.provided.RecordingBuilderListener;
import io.vidocq.cyrano.mp.rest.client.api.provided.RecordingResolver;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.spi.RestClientBuilderListener;
import org.eclipse.microprofile.rest.client.spi.RestClientBuilderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RestClientBuilder#newBuilder()} on the module path: the repackaged API looks up the
 * {@link RestClientBuilderResolver} and the {@link RestClientBuilderListener}s with
 * {@link java.util.ServiceLoader} (the listeners: MP Rest Client 4.0 §10.1), which a named module may only
 * do for services its descriptor {@code uses}.
 */
class RestClientBuilderLookupTest {

    @TempDir
    Path dir;

    private ClassLoader previousTccl;

    @BeforeEach
    void clearState() {
        previousTccl = Thread.currentThread().getContextClassLoader();
        RestClientBuilderResolver.setInstance(null);
        System.clearProperty(RecordingResolver.CALLS);
        System.clearProperty(RecordingBuilderListener.CALLS);
    }

    @AfterEach
    void restoreState() {
        Thread.currentThread().setContextClassLoader(previousTccl);
        RestClientBuilderResolver.setInstance(null);
    }

    @Test
    void runsOnTheModulePath() {
        assertTrue(RestClientBuilder.class.getModule().isNamed(),
                "these tests are only meaningful when the API is a named module");
    }

    @Test
    void newBuilder_findsTheResolverAndTheBuilderListenersOfAnotherModule() {
        ModuleLayer layer = ProviderModule.named(dir, "cyrano.test.providers")
                .provides(RestClientBuilderResolver.class, RecordingResolver.class)
                .provides(RestClientBuilderListener.class, RecordingBuilderListener.class)
                .layer();
        Thread.currentThread().setContextClassLoader(layer.findLoader("cyrano.test.providers"));

        RestClientBuilder.newBuilder();

        assertEquals(1, Integer.getInteger(RecordingResolver.CALLS, 0), "resolver of the other module");
        assertEquals(1, Integer.getInteger(RecordingBuilderListener.CALLS, 0), "builder listener of the other module");
    }

    @Test
    void withoutAnyResolver_theLookupReportsTheMissingImplementation() {
        IllegalStateException missing = assertThrows(IllegalStateException.class, RestClientBuilderResolver::instance);
        assertEquals("No RestClientBuilderResolver implementation found!", missing.getMessage());
    }
}
