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

import io.vidocq.cyrano.internal.CyranoClientConfiguration;
import io.vidocq.cyrano.internal.CyranoHttpTransport;
import io.vidocq.cyrano.internal.CyranoInterfaceScanner;
import io.vidocq.cyrano.internal.CyranoInvocationHandler;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resolution chain of {@link ClientProxyRegistry} (codegen audit CG-01) —
 * generated artifacts first, runtime Class-File generation strictly as fallback:
 * <ol>
 *   <li>ServiceLoader of {@code ClientProxyFactory} (JPMS-friendly),</li>
 *   <li>naming convention {@code Class.forName(iface + "$$CyranoClient")},</li>
 *   <li>runtime {@code CyranoProxyGenerator} (documented fallback).</li>
 * </ol>
 */
class ClientProxyRegistryTest {

    /** No generated artifact exists for this one — must hit the runtime fallback. */
    @Path("/fallback")
    public interface FallbackApi {
        @GET
        String read();
    }

    /** Handler double recording invocations — proves the generated proxy's index wiring. */
    private static final class RecordingHandler extends CyranoInvocationHandler {
        final List<int[]> calls = new ArrayList<>();
        final List<Object[]> argsSeen = new ArrayList<>();
        Object canned = "canned";

        RecordingHandler(List<io.vidocq.cyrano.internal.RequestSpec> specs) {
            super(URI.create("http://localhost:1"), specs,
                    new CyranoHttpTransport(new CyranoClientConfiguration()));
        }

        @Override
        public Object invoke(Object proxy, int methodIndex, Object[] args) {
            calls.add(new int[] { methodIndex });
            argsSeen.add(args);
            return canned;
        }
    }

    @BeforeEach
    void reset() {
        ClientProxyRegistry.resetForTests();
    }

    @Test
    void namingConventionTier_findsHandWrittenGeneratedClass() {
        var resolved = ClientProxyRegistry.resolve(FixtureApi.class);
        assertEquals(ClientProxyRegistry.Source.PRE_GENERATED, resolved.source());
        assertEquals(1, ClientProxyRegistry.preGeneratedHits());
        assertEquals(0, ClientProxyRegistry.runtimeGeneratedHits());
    }

    @Test
    void serviceLoaderTier_winsOverNamingConvention() {
        var resolved = ClientProxyRegistry.resolve(LoaderApi.class);
        assertEquals(ClientProxyRegistry.Source.SERVICE_LOADER, resolved.source());
        assertEquals(1, ClientProxyRegistry.serviceLoaderHits());
        assertEquals(0, ClientProxyRegistry.preGeneratedHits());
    }

    @Test
    void runtimeTier_isTheDocumentedFallback() {
        var resolved = ClientProxyRegistry.resolve(FallbackApi.class);
        assertEquals(ClientProxyRegistry.Source.RUNTIME_GENERATED, resolved.source());
        assertEquals(1, ClientProxyRegistry.runtimeGeneratedHits());
        var handler = new RecordingHandler(resolved.specs());
        Object proxy = resolved.instantiator().apply(handler);
        assertInstanceOf(FallbackApi.class, proxy);
        assertEquals("canned", ((FallbackApi) proxy).read());
    }

    @Test
    void resolution_isCachedPerInterface() {
        ClientProxyRegistry.resolve(FixtureApi.class);
        ClientProxyRegistry.resolve(FixtureApi.class);
        assertEquals(1, ClientProxyRegistry.preGeneratedHits(),
                "Second resolve must come from the cache");
    }

    @Test
    void generatedSpecs_matchScannerOutput() {
        var resolved = ClientProxyRegistry.resolve(FixtureApi.class);
        var scanned = CyranoInterfaceScanner.scan(FixtureApi.class);
        assertEquals(scanned.size(), resolved.specs().size());
        for (var spec : resolved.specs()) {
            assertEquals(scanned.get(spec.method()), spec);
        }
    }

    @Test
    void generatedProxy_wiresMethodIndexesAndBoxing() {
        var resolved = ClientProxyRegistry.resolve(FixtureApi.class);
        var handler = new RecordingHandler(resolved.specs());
        Object proxy = resolved.instantiator().apply(handler);
        var api = assertInstanceOf(FixtureApi.class, proxy);
        assertInstanceOf(java.io.Closeable.class, proxy);

        api.get("abc");
        handler.canned = 42L;
        long result = api.post("body");

        assertEquals(42L, result);
        assertEquals(0, handler.calls.get(0)[0], "get() must dispatch to methodIndex 0");
        assertEquals(1, handler.calls.get(1)[0], "post() must dispatch to methodIndex 1");
        assertArrayEquals(new Object[] { "abc" }, handler.argsSeen.get(0));
        assertArrayEquals(new Object[] { "body" }, handler.argsSeen.get(1));
    }

    @Test
    void invalidInterface_propagatesDefinitionError() {
        assertThrows(IllegalArgumentException.class,
                () -> ClientProxyRegistry.resolve(String.class));
    }
}
