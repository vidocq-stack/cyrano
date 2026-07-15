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
 * <p>Java Modules-free declaration: exposed via
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


