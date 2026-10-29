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

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The filter and interceptor chains are read on every call: they are sorted once, and sorted again only after a
 * registration (MicroProfile Rest Client 4.0 §4.2, JAX-RS §6.3 ordering by priority).
 */
class CyranoClientConfigurationTest {

    static final class Late implements ClientRequestFilter, ClientResponseFilter, ReaderInterceptor, WriterInterceptor {
        @Override public void filter(ClientRequestContext request) {
            // only its place in the chain matters
        }
        @Override public void filter(ClientRequestContext request, ClientResponseContext response) {
            // only its place in the chain matters
        }
        @Override public Object aroundReadFrom(ReaderInterceptorContext context) { return null; }
        @Override public void aroundWriteTo(WriterInterceptorContext context) {
            // only its place in the chain matters
        }
    }

    static final class Early implements ClientRequestFilter, ClientResponseFilter {
        @Override public void filter(ClientRequestContext request) {
            // only its place in the chain matters
        }
        @Override public void filter(ClientRequestContext request, ClientResponseContext response) {
            // only its place in the chain matters
        }
    }

    @Test
    void theChainsAreBuiltOnceBetweenTwoRegistrations() {
        var configuration = new CyranoClientConfiguration();
        configuration.registerProvider(new Late(), Map.of());

        assertSame(configuration.getRequestFilters(), configuration.getRequestFilters());
        assertSame(configuration.getResponseFilters(), configuration.getResponseFilters());
        assertSame(configuration.getReaderInterceptors(), configuration.getReaderInterceptors());
        assertSame(configuration.getWriterInterceptors(), configuration.getWriterInterceptors());
    }

    @Test
    void aRegistrationAfterTheFirstCallIsInTheNextChain() {
        var configuration = new CyranoClientConfiguration();
        Late late = new Late();
        configuration.registerProvider(late, Map.of(ClientRequestFilter.class, 5000, ClientResponseFilter.class, 5000));
        assertEquals(List.of(late), configuration.getRequestFilters());

        Early early = new Early();
        configuration.registerProvider(early, Map.of(ClientRequestFilter.class, 1000, ClientResponseFilter.class, 1000));

        assertEquals(List.of(early, late), configuration.getRequestFilters());
        assertEquals(List.of(late, early), configuration.getResponseFilters());
    }
}
