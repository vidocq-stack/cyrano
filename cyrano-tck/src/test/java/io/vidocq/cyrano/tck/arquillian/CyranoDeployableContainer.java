/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
 * dédié au TCK MicroProfile Rest Client 4.0.
 *
 * <p>Cyrano est une implémentation cliente REST : aucune ressource JAX-RS
 * serveur n'a besoin d'être déployée pour passer le TCK. Le TCK gère son
 * propre backend HTTP via <strong>WireMock</strong>
 * ({@code WiremockArquillianTest.setupServer()}). Le container se contente
 * donc :</p>
 * <ul>
 *   <li>d'accepter les {@link Archive} fournies par {@code @Deployment}
 *       (sans rien déployer sur un serveur),</li>
 *   <li>de retourner un {@link ProtocolDescription} {@code Local} pour
 *       qu'Arquillian exécute les tests dans la JVM de test (mode «&nbsp;as-client&nbsp;»
 *       par défaut, sans nécessiter d'enrichissement de bytecode des tests).</li>
 * </ul>
 *
 * <p>Découverte via {@code META-INF/services/}
 * {@code org.jboss.arquillian.container.spi.client.container.DeployableContainer}
 * + {@code arquillian.xml} (qualifier {@code cyrano}, défaut).</p>
 *
 * <p>Pour les tests CDI du TCK
 * ({@code org.eclipse.microprofile.rest.client.tck.cditests.*}), l'injection
 * {@code @Inject @RestClient} est couverte par
 * {@code cyrano-cdi-vauban} — l'enrichissement Arquillian/CDI sera ajouté
 * itérativement si la BCE Cyrano + Vauban n'est pas suffisante.</p>
 */
public class CyranoDeployableContainer implements DeployableContainer<CyranoContainerConfiguration> {

    @Override
    public Class<CyranoContainerConfiguration> getConfigurationClass() {
        return CyranoContainerConfiguration.class;
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        // « Local » : exécution des tests dans la JVM de test, sans
        // sérialisation ni transfert vers un container distant.
        return new ProtocolDescription("Local");
    }

    @Override
    public void setup(CyranoContainerConfiguration configuration) {
        // rien à initialiser.
    }

    @Override
    public void start() throws LifecycleException {
        // WireMock est démarré par {@link CyranoArquillianExtension#register}
        // au boot de la JVM de test ; rien à faire ici. Le cycle de vie
        // Arquillian {@code start()}/{@code stop()} se déclenche trop tôt
        // pour servir les tests TCK ; voir {@link WireMockTestBackend}.
        WireMockTestBackend.start();
    }

    @Override
    public void stop() throws LifecycleException {
        // No-op — WireMock est arrêté par le shutdown hook JVM.
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        // Démarre le container Vauban CDI pour ce déploiement TCK :
        // extrait les propriétés MP Config de l'archive, les exporte comme
        // system properties, puis boot Vauban avec CyranoRestClientCdiExtension
        // et les classes de l'archive. WireMock (backend HTTP) est déjà actif.
        VaubanTckBootstrap.deploy(archive);
        return new ProtocolMetaData();
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        // Arrête le container Vauban et nettoie les system properties de config.
        VaubanTckBootstrap.undeploy();
    }

    @Override
    public void deploy(Descriptor descriptor) {
        // No-op (descriptor-based deployment non utilisé par le TCK MP Rest Client).
    }

    @Override
    public void undeploy(Descriptor descriptor) {
        // No-op.
    }
}






