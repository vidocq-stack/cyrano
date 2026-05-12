/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
 * Smoke test du bootstrap Cyrano — vérifie que les modules cyrano-core et cyrano-api
 * sont correctement chargés sans Arquillian.
 *
 * <p>À M0 (bootstrap), seul le chargement des classes spec est validé. La construction
 * de proxies via {@link RestClientBuilder} nécessite l'implémentation
 * {@code CyranoRestClientBuilder} (M1) — les tests correspondants seront ajoutés
 * progressivement.</p>
 *
 * <p>Exécuté par le profil Maven {@code smoke} (actif par défaut) via le script
 * {@code run-official-tck-mp-rest-client-4.0.sh}.</p>
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
        // Vérifie que les classes MicroProfile Rest Client 4.0 sont bien sur le module-path
        assertNotNull(RestClientBuilder.class);
        assertNotNull(RegisterRestClient.class);
        assertNotNull(RestClient.class);
    }

    @Test
    void jaxrs_annotations_are_loadable() {
        // Les annotations JAX-RS sont utilisées sur les interfaces client (spec §3)
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
        // §10 (SPI) : RestClientBuilder.newBuilder() doit retourner un builder via
        // ServiceLoader — ici Cyrano via META-INF/services + provides JPMS.
        RestClientBuilder builder = RestClientBuilder.newBuilder();
        assertNotNull(builder, "Le SPI RestClientBuilderResolver doit fournir un builder");
        assertEquals("io.vidocq.cyrano.internal.CyranoRestClientBuilder",
                builder.getClass().getName(),
                "Le builder doit être l'implémentation Cyrano");
    }

    @Test
    void rest_client_builder_produces_cyrano_proxy_spec_section3() {
        // §3 : build(Class) doit produire une instance qui implémente l'interface.
        SampleClient client = RestClientBuilder.newBuilder()
                .baseUri(URI.create("http://127.0.0.1:1"))
                .build(SampleClient.class);
        assertNotNull(client);
        assertNotNull(client.getClass().getName());
        // Le proxy doit être nommé Cyrano$<SimpleName> (Class-File API JEP 484, pas Proxy)
        org.junit.jupiter.api.Assertions.assertTrue(
                client.getClass().getName().endsWith("Cyrano$SampleClient"),
                "Le proxy doit être généré via Class-File API et nommé Cyrano$SampleClient "
                        + "— était " + client.getClass().getName());
    }
}

