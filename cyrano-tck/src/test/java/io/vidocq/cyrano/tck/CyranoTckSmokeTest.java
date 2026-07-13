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
package io.vidocq.cyrano.tck;

import io.vidocq.cyrano.spi.Cyrano;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke Cyrano bootstrap test — checks that cyrano-core and cyrano-api modules
 * are properly loaded without Arquillian.
 *
 * <p>At M0 (bootstrap), only the loading of spec classes is validated. Proxy
 * construction via {@link RestClientBuilder} requires the {@code CyranoRestClientBuilder}
 * implementation (M1) — the corresponding tests will be added progressively.</p>
 *
 * <p>Run by the Maven {@code smoke} (default active) profile via the
 * {@code run-official-tck-mp-rest-client-4.0.sh} script.</p>
 */
class CyranoTckSmokeTest {

    @Test
    void cyrano_metadata_is_accessible() {
        assertEquals("cyrano", Cyrano.IMPLEMENTATION_NAME);
        assertEquals("4.0", Cyrano.SPEC_VERSION);
        assertNotNull(Cyrano.IMPLEMENTATION_VERSION);
    }

    @Test
    void rest_client_api_classes_are_loadable() {
        //Check that MicroProfile Rest Client 4.0 classes are on the module path
        assertNotNull(RestClientBuilder.class);
        assertNotNull(RegisterRestClient.class);
        assertNotNull(RestClient.class);
    }

    @Test
    void jaxrs_annotations_are_loadable() {
        //JAX-RS annotations are used on client interfaces (spec §3)
        assertNotNull(GET.class);
        assertNotNull(Path.class);
        assertNotNull(PathParam.class);
        assertNotNull(Produces.class);
        assertNotNull(MediaType.APPLICATION_JSON);
    }

    @Path("/sample")
    interface SampleClient {
        @GET
        String ping();
    }

    @Test
    void rest_client_builder_is_resolved_via_service_loader_spec_section10() {
        //§10 (SPI): RestClientBuilder.newBuilder() must return a builder via
        //ServiceLoader — here Cyrano via META-INF/services + provides Java Modules.
        RestClientBuilder builder = RestClientBuilder.newBuilder();
        assertNotNull(builder, "The RestClientBuilderResolver SPI must provide a builder");
        assertEquals("io.vidocq.cyrano.internal.CyranoRestClientBuilder",
                builder.getClass().getName(),
                "The builder must be the Cyrano implementation");
    }

    @Test
    void rest_client_builder_produces_cyrano_proxy_spec_section3() {
        //§3: build(Class) must produce an instance that implements the interface.
        SampleClient client = RestClientBuilder.newBuilder()
                .baseUri(URI.create("http://127.0.0.1:1"))
                .build(SampleClient.class);
        assertNotNull(client);
        String name = client.getClass().getName();
        assertNotNull(name);
        // The proxy must be a Class-File API (JEP 484) class named `Cyrano$<binaryWithoutPkg>`,
        // where nested-class `$` separators are flattened to `_` to keep a single legible
        // `Cyrano$` discriminator (see CyranoProxyGenerator). For a nested interface
        // `CyranoTckSmokeTest.SampleClient` this resolves to
        // `Cyrano$CyranoTckSmokeTest_SampleClient` — not `Cyrano$SampleClient`.
        org.junit.jupiter.api.Assertions.assertTrue(
                name.contains("Cyrano$") && name.endsWith("SampleClient"),
                "The proxy must be generated via the Class-File API with a single `Cyrano$` "
                        + "prefix and end with the interface simple name — was " + name);
    }
}

