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
 * Register {@link CyranoDeployableContainer} with the Arquillian framework
 * via the {@link LoadableExtension} SPI. The {@code ContainerRegistryCreator}
 * in Arquillian consults this SPI (and not a direct {@code ServiceLoader} on
 * {@link DeployableContainer}) to discover the available containers.
 *
 * <p>JPMS-free declaration: exposed via
 * {@code META-INF/services/org.jboss.arquillian.core.spi.LoadableExtension}.</p>
 */
public class CyranoArquillianExtension implements LoadableExtension {

    @Override
    public void register(ExtensionBuilder builder) {
        //Starts WireMock immediately, recording the extension —
        //i.e. when Arquillian builds his Manager at the start of the
        // test JVM (before any @BeforeSuite / @BeforeClass). Binding to the
        // cycle de vie {@code start()}/{@code stop()} de {@link CyranoDeployableContainer}
        //was insufficient: Arquillian calls start/stop once at the boot,
        //Out of the window where TCK tests access WireMock.
        WireMockTestBackend.start();

        builder.service(DeployableContainer.class, CyranoDeployableContainer.class);
        builder.service(TestEnricher.class, CyranoTestEnricher.class);
    }
}


