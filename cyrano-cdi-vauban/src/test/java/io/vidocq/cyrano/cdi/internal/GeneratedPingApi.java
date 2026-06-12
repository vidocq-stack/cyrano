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
package io.vidocq.cyrano.cdi.internal;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Top-level test client with a hand-written {@code $$CyranoClient} companion —
 * proves that {@code @Inject @RestClient} (through the BCE synthetic creator and
 * {@code RestClientBuilder}) is served by the generated tier, not by runtime
 * proxy generation (CG-01 D6).
 */
@RegisterRestClient(baseUri = "http://127.0.0.1:" + GeneratedPingApi.PORT_STRING)
@Path("/gping")
public interface GeneratedPingApi {

    /** Distinct from {@code TestPort.SERVER_PORT} to avoid cross-test collisions. */
    String PORT_STRING = "18858";
    int PORT = 18858;

    @GET
    @Path("/{name}")
    @Produces(MediaType.TEXT_PLAIN)
    String greet(@PathParam("name") String name);
}
