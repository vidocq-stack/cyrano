/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.vidocq.cyrano.tck.arquillian;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;
import org.jboss.arquillian.test.spi.TestEnricher;

/**
 * Enregistre {@link CyranoDeployableContainer} auprès du framework Arquillian
 * via le SPI {@link LoadableExtension}. Le {@code ContainerRegistryCreator}
 * d'Arquillian consulte ce SPI (et non un {@code ServiceLoader} direct sur
 * {@link DeployableContainer}) pour découvrir les containers disponibles.
 *
 * <p>Déclaration JPMS-free : exposée via
 * {@code META-INF/services/org.jboss.arquillian.core.spi.LoadableExtension}.</p>
 */
public class CyranoArquillianExtension implements LoadableExtension {

    @Override
    public void register(ExtensionBuilder builder) {
        // Démarre WireMock immédiatement, à l'enregistrement de l'extension —
        // c.-à-d. lorsque Arquillian construit son Manager au démarrage de la
        // JVM de test (avant tout @BeforeSuite / @BeforeClass). Le binding au
        // cycle de vie {@code start()}/{@code stop()} de {@link CyranoDeployableContainer}
        // était insuffisant : Arquillian appelle start/stop une fois au boot,
        // hors de la fenêtre où les tests TCK accèdent à WireMock.
        WireMockTestBackend.start();

        builder.service(DeployableContainer.class, CyranoDeployableContainer.class);
        builder.service(TestEnricher.class, CyranoTestEnricher.class);
    }
}


