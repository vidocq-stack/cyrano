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

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Small double {@link Response} for unit tests — avoids dependency on one
 * {@code RuntimeDelegate} JAX-RS (Cassini) in test scope, which is not drawn by
 * {@code cyrano-core}. The full scenario {@code Response.ok(...).build()} is
 * covered by the official TCK (where Cassini is test-scope).
 */
final class StubResponse extends Response {
    private final int status;
    private final Object entity;
    private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
    private final MultivaluedMap<String, String> stringHeaders = new MultivaluedHashMap<>();

    static StubResponse ofString(int status, String body) {
        return new StubResponse(status, body);
    }

    private StubResponse(int status, Object entity) {
        this.status = status;
        this.entity = entity;
    }

    @Override public int getStatus() { return status; }
    @Override public StatusType getStatusInfo() { return Status.fromStatusCode(status); }
    @Override public Object getEntity() { return entity; }
    @SuppressWarnings("unchecked")
    @Override public <T> T readEntity(Class<T> entityType) {
        if (entityType == String.class) return (T) (entity == null ? null : entity.toString());
        throw new UnsupportedOperationException();
    }
    @Override public <T> T readEntity(jakarta.ws.rs.core.GenericType<T> entityType) { throw new UnsupportedOperationException(); }
    @Override public <T> T readEntity(Class<T> entityType, Annotation[] annotations) { throw new UnsupportedOperationException(); }
    @Override public <T> T readEntity(jakarta.ws.rs.core.GenericType<T> entityType, Annotation[] annotations) { throw new UnsupportedOperationException(); }
    @Override public boolean hasEntity() { return entity != null; }
    @Override public boolean bufferEntity() { return false; }
    @Override public void close() { }
    @Override public MediaType getMediaType() { return MediaType.WILDCARD_TYPE; }
    @Override public Locale getLanguage() { return null; }
    @Override public int getLength() { return entity == null ? -1 : entity.toString().length(); }
    @Override public Set<String> getAllowedMethods() { return Set.of(); }
    @Override public Map<String, NewCookie> getCookies() { return Map.of(); }
    @Override public jakarta.ws.rs.core.EntityTag getEntityTag() { return null; }
    @Override public Date getDate() { return null; }
    @Override public Date getLastModified() { return null; }
    @Override public URI getLocation() { return null; }
    @Override public Set<jakarta.ws.rs.core.Link> getLinks() { return Set.of(); }
    @Override public boolean hasLink(String relation) { return false; }
    @Override public jakarta.ws.rs.core.Link getLink(String relation) { return null; }
    @Override public jakarta.ws.rs.core.Link.Builder getLinkBuilder(String relation) { throw new UnsupportedOperationException(); }
    @Override public MultivaluedMap<String, Object> getMetadata() { return headers; }
    @Override public MultivaluedMap<String, Object> getHeaders() { return headers; }
    @Override public MultivaluedMap<String, String> getStringHeaders() { return stringHeaders; }
    @Override public String getHeaderString(String name) { return null; }
}

