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

import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Test double of the Jakarta REST {@link RuntimeDelegate}: cyrano-core has no Jakarta REST
 * implementation in test scope (Cassini only comes with the TCK), yet {@link MediaType#valueOf}
 * and {@link MediaType#toString()} go through the delegate, and {@link Cookie} resolves its
 * delegate when the class initialises. Only the {@link MediaType} header delegate works;
 * everything else is unsupported.
 */
final class TestRuntimeDelegate extends RuntimeDelegate {

    private TestRuntimeDelegate() {
    }

    /** Installs the double once for the whole test JVM (idempotent). */
    static void install() {
        RuntimeDelegate.setInstance(new TestRuntimeDelegate());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HeaderDelegate<T> createHeaderDelegate(Class<T> type) {
        if (type == MediaType.class) {
            return (HeaderDelegate<T>) new MediaTypeDelegate();
        }
        if (type == Cookie.class || type == NewCookie.class) {
            // Cookie and NewCookie resolve their delegate in a static initializer.
            return new UnsupportedDelegate<>();
        }
        throw new UnsupportedOperationException("No header delegate for " + type);
    }

    private static final class UnsupportedDelegate<T> implements HeaderDelegate<T> {
        @Override
        public T fromString(String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString(T value) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MediaTypeDelegate implements HeaderDelegate<MediaType> {
        @Override
        public MediaType fromString(String value) {
            String[] parts = value.split(";");
            String[] typeAndSubtype = parts[0].trim().split("/", 2);
            Map<String, String> parameters = new LinkedHashMap<>();
            for (int i = 1; i < parts.length; i++) {
                String[] kv = parts[i].trim().split("=", 2);
                parameters.put(kv[0].trim(), kv.length > 1 ? kv[1].trim() : "");
            }
            return new MediaType(typeAndSubtype[0], typeAndSubtype.length > 1 ? typeAndSubtype[1] : "*", parameters);
        }

        @Override
        public String toString(MediaType value) {
            StringBuilder sb = new StringBuilder(value.getType()).append('/').append(value.getSubtype());
            value.getParameters().forEach((k, v) -> sb.append(';').append(k).append('=').append(v));
            return sb.toString();
        }
    }

    @Override
    public UriBuilder createUriBuilder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder createResponseBuilder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Variant.VariantListBuilder createVariantListBuilder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T createEndpoint(Application application, Class<T> endpointType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Link.Builder createLinkBuilder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SeBootstrap.Configuration.Builder createConfigurationBuilder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Application application,
                                                           SeBootstrap.Configuration configuration) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Class<? extends Application> clazz,
                                                           SeBootstrap.Configuration configuration) {
        throw new UnsupportedOperationException();
    }

    @Override
    public EntityPart.Builder createEntityPartBuilder(String partName) {
        throw new UnsupportedOperationException();
    }
}
