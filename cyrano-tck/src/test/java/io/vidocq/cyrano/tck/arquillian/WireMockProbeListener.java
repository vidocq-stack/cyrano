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
 * Starts WireMock from TestNG initialization (loaded via
 * {@code META-INF/services/org.testng.ITestNGListener}) and probes
 * its availability before each TestNG method (config or test).
 *
 * <p>Binding on the Arquillian life cycle turned out to be too late:
 * {@code LoadableExtension.register()} and {@code DeployableContainer.start()}
 * are only called after {@code @AfterSuite}, while TCK {@code @BeforeMethod}
 * hooks (notably {@code resetWiremock}) need the HTTP backend available from
 * the very first call. Starting WireMock in the static block of this listener
 * ensures it is ready before any TestNG method is executed.</p>
 */
public class WireMockProbeListener implements IInvokedMethodListener {

    static {
        WireMockTestBackend.start();
    }

    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {
        //Hook retained to reactivate diagnosis in case of regression.
    }
}




