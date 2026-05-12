/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.vidocq.cyrano.tck.arquillian;

import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestResult;

/**
 * Démarre WireMock dès l'initialisation TestNG (chargé via
 * {@code META-INF/services/org.testng.ITestNGListener}) et sonde
 * sa disponibilité avant chaque méthode TestNG (config ou test).
 *
 * <p>Le binding sur le cycle de vie Arquillian s'est révélé trop
 * tardif : {@code LoadableExtension.register()} et
 * {@code DeployableContainer.start()} ne sont appelés qu'après
 * {@code @AfterSuite}, alors que les {@code @BeforeMethod} TCK
 * (notamment {@code resetWiremock}) ont besoin du backend HTTP
 * disponible dès le premier appel. Démarrer WireMock dans le bloc
 * statique du listener garantit qu'il est prêt avant toute exécution
 * de méthode TestNG.</p>
 */
public class WireMockProbeListener implements IInvokedMethodListener {

    static {
        WireMockTestBackend.start();
    }

    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {
        // Hook conservé pour réactiver le diagnostic en cas de régression.
    }
}




