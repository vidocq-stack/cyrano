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
package io.vidocq.cyrano.cdi.internal;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer;

import java.io.Closeable;
import java.io.IOException;

/**
 * CDI disposer for synthetic REST clients — spec MP Rest Client 4.0 §8.1:
 * all proxies are {@link Closeable}. Calls {@code close()} on the proxy
 * when the CDI scope ends.
 */
public class CyranoRestClientSyntheticDisposer implements SyntheticBeanDisposer<Object> {

    @Override
    public void dispose(Object instance, Instance<Object> lookup, Parameters params) {
        if (instance instanceof Closeable c) {
            try {
                c.close();
            } catch (IOException ignored) {
                // close() ne lance pas d'IOException en pratique
            }
        }
    }
}
