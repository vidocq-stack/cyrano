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




