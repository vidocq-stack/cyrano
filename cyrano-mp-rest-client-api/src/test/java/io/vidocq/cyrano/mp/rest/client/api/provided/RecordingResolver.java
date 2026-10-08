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
package io.vidocq.cyrano.mp.rest.client.api.provided;

import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.spi.RestClientBuilderResolver;

/**
 * A resolver shipped in a module of its own (see {@code ProviderModule}). It hands out no builder:
 * the test only needs to know the lookup reached it, which it records in a system property because
 * the copy loaded from that module is not the class the test sees.
 */
public final class RecordingResolver extends RestClientBuilderResolver {

    public static final String CALLS = "cyrano.test.resolver.calls";

    @Override
    public RestClientBuilder newBuilder() {
        System.setProperty(CALLS, Integer.toString(Integer.getInteger(CALLS, 0) + 1));
        return null;
    }
}
