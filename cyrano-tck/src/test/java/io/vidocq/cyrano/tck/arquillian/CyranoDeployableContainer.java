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
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

/**
 * Container Arquillian Cyrano — <strong>embedded local container</strong>
 * dedicated to TCK MicroProfile Rest Client 4.0.
 *
 * <p>Cyrano is a REST client implementation: no server-side JAX-RS resources
 * need to be deployed to pass the TCK. The TCK manages its own HTTP backend
 * via <strong>WireMock</strong> ({@code WiremockArquillianTest.setupServer()}).
 * The container therefore simply:</p>
 * <ul>
 *   <li>accepts the {@link Archive} provided by {@code @Deployment}
 *       (without deploying anything to a server),</li>
 *   <li>returns a {@link ProtocolDescription} {@code Local} so that Arquillian
 *       runs the tests inside the test JVM ("as-client" mode without bytecode
 *       enrichment).</li>
 * </ul>
 *
 * <p>Discovered via {@code META-INF/services/}
 * {@code org.jboss.arquillian.container.spi.client.container.DeployableContainer}
 * + {@code arquillian.xml} (qualifier {@code cyrano}, default).</p>
 *
 * <p>For TCK CDI tests
 * ({@code org.eclipse.microprofile.rest.client.tck.cditests.*}), the
 * {@code @Inject @RestClient} injection is covered by {@code cyrano-cdi-vauban}
 * — Arquillian/CDI enrichment will be added if the Cyrano BCE + Vauban combo is
 * not sufficient.</p>
 */
public class CyranoDeployableContainer implements DeployableContainer<CyranoContainerConfiguration> {

    @Override
    public Class<CyranoContainerConfiguration> getConfigurationClass() {
        return CyranoContainerConfiguration.class;
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        //"Local": test execution in the FMV test, without
        //serialization or transfer to a remote container.
        return new ProtocolDescription("Local");
    }

    @Override
    public void setup(CyranoContainerConfiguration configuration) {
        //Nothing to initialize.
    }

    @Override
    public void start() throws LifecycleException {
        //WireMock is started by {@link CyranoArquillianExtension#register}
        //the JVM test boot; Nothing to do here. Life cycle
        //Arquillian {@code start()}/{@code stop()} triggers too soon
        //to serve TCK tests; see {@link WireMockTestBackend}.
        WireMockTestBackend.start();
    }

    @Override
    public void stop() throws LifecycleException {
        //No-op — WireMock is stopped by the shutdown hook JVM.
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        //Start the Vauban CDI container for this TCK deployment:
        //extracts MP Config properties from the archive, exports them as
        // system properties, then boot Vauban with CyranoRestClientCdiExtension
        //and the archive classes. WireMock is already active.
        VaubanTckBootstrap.deploy(archive);
        return new ProtocolMetaData();
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        //Stop the Vauban container and clean the config properties system.
        VaubanTckBootstrap.undeploy();
    }

    @Override
    public void deploy(Descriptor descriptor) {
        //No-op (descriptor-based deployment not used by TCK MP Rest Client).
    }

    @Override
    public void undeploy(Descriptor descriptor) {
        // No-op.
    }
}






