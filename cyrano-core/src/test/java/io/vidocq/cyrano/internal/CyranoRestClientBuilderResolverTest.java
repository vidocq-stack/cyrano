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
package io.vidocq.cyrano.internal;

import io.vidocq.cyrano.internal.provided.RecordingBuilderListener;
import io.vidocq.cyrano.internal.provided.RecordingClientListener;
import io.vidocq.cyrano.runtime.CyranoRestClientBuilderResolver;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.spi.RestClientBuilderListener;
import org.eclipse.microprofile.rest.client.spi.RestClientBuilderResolver;
import org.eclipse.microprofile.rest.client.spi.RestClientListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Building a client on the module path. Cyrano looks the listeners up with
 * {@link java.util.ServiceLoader} (MP Rest Client 4.0 §10.1 and §10.2), which a named module may only
 * do for services its descriptor {@code uses}; the listeners usually live in another module
 * (humboldt-rest provides a {@link RestClientListener}).
 */
class CyranoRestClientBuilderResolverTest {

    /** A client interface; building it never sends a request. */
    @Path("/ping")
    public interface PingApi {
        @GET
        String ping();
    }

    @TempDir
    java.nio.file.Path dir;

    private ClassLoader previousTccl;

    @BeforeEach
    void clearState() {
        previousTccl = Thread.currentThread().getContextClassLoader();
        RestClientBuilderResolver.setInstance(null);
        System.clearProperty(RecordingBuilderListener.CALLS);
        System.clearProperty(RecordingClientListener.CALLS);
    }

    @AfterEach
    void restoreState() {
        Thread.currentThread().setContextClassLoader(previousTccl);
        RestClientBuilderResolver.setInstance(null);
    }

    @Test
    void runsOnTheModulePath() {
        assertTrue(CyranoRestClientBuilder.class.getModule().isNamed(),
                "these tests are only meaningful when cyrano-core is a named module");
    }

    @Test
    void newBuilder_isServedByCyrano() {
        assertInstanceOf(CyranoRestClientBuilder.class, RestClientBuilder.newBuilder());
    }

    @Test
    void build_returnsAClient() {
        PingApi client = new CyranoRestClientBuilderResolver().newBuilder()
                .baseUri(URI.create("http://localhost:1"))
                .build(PingApi.class);

        assertNotNull(client);
    }

    @Test
    void build_callsTheListenersOfAnotherModule_spec_section10_2() {
        ModuleLayer layer = ProviderModule.named(dir, "cyrano.test.listeners")
                .provides(RestClientBuilderListener.class, RecordingBuilderListener.class)
                .provides(RestClientListener.class, RecordingClientListener.class)
                .layer();
        Thread.currentThread().setContextClassLoader(layer.findLoader("cyrano.test.listeners"));

        RestClientBuilder.newBuilder().baseUri(URI.create("http://localhost:1")).build(PingApi.class);

        assertEquals(1, Integer.getInteger(RecordingClientListener.CALLS, 0), "RestClientListener.onNewClient");
        assertTrue(Integer.getInteger(RecordingBuilderListener.CALLS, 0) >= 1,
                "RestClientBuilderListener.onNewBuilder, called "
                        + Integer.getInteger(RecordingBuilderListener.CALLS, 0) + " time(s)");
    }
}
