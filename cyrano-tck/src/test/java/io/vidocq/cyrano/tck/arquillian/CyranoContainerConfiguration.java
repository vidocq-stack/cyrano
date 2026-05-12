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
 * Configuration Arquillian du container Cyrano TCK — POJO sans propriété requise.
 *
 * <p>Cyrano étant un <strong>client REST</strong> (et non un serveur), aucun
 * port ni hôte ne doit être configuré : le TCK MicroProfile Rest Client 4.0
 * démarre lui-même un backend <strong>WireMock</strong> dans la JVM de test
 * via {@code WiremockArquillianTest.setupServer()} ; le rôle du
 * {@link CyranoDeployableContainer} se réduit à accepter les déploiements
 * Arquillian et à exécuter les tests en local (protocole Arquillian
 * {@code Local}).</p>
 */
public class CyranoContainerConfiguration implements ContainerConfiguration {

    @Override
    public void validate() throws ConfigurationException {
        // rien à valider : aucune propriété requise.
    }
}

