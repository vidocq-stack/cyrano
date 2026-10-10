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
package io.vidocq.cyrano.it.openliberty;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Calls the stub through Cyrano and answers {@code <message>|<length>|<proxy class>}, so the test
 * checks the call and which proxy made it.
 */
@Path("/call")
@RequestScoped
@Produces(MediaType.TEXT_PLAIN)
public class CallingResource {

    @Inject
    @RestClient
    GreetingClient injected;

    @GET
    @Path("/injected/{name}")
    public String injected(@PathParam("name") String name) {
        return describe(injected.greet(name), injected);
    }

    @GET
    @Path("/plain/{name}")
    public String plain(@PathParam("name") String name, @Context UriInfo uri) {
        PlainClient client = RestClientBuilder.newBuilder().baseUri(uri.getBaseUri()).build(PlainClient.class);
        return describe(client.greet(name), client);
    }

    private static String describe(Greeting greeting, Object client) {
        return greeting.message + "|" + greeting.length + "|" + client.getClass().getName();
    }
}
