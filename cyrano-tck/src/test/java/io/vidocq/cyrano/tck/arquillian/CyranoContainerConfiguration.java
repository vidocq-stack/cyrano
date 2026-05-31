/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.vidocq.cyrano.tck.arquillian;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Arquillian configuration of container Cyrano TCK — POJO without property required.
 *
 * <p>Cyrano being a <strong> client REST</strong> (not a server), none
 * port and host should not be configured: the TCK MicroProfile Rest Client 4.0
 * starts a <strong>WireMock</strong> backend in the test JVM
 * via {@code WiremockArquillianTest.setupServer()}; the role of
 * {@link CyranoDeployableContainer} reduces itself to accepting deployments
 * Arquillian and to perform the tests in local (Arquillian protocol)
 * {@code Local}).</p>
 */
public class CyranoContainerConfiguration implements ContainerConfiguration {

    @Override
    public void validate() throws ConfigurationException {
        //nothing to validate: no property required.
    }
}

