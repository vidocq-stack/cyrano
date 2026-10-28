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
package io.vidocq.cyrano.internal.gen;

import io.vidocq.cyrano.spi.gen.ClientDescriptor;
import io.vidocq.cyrano.spi.gen.ClientInvoker;
import io.vidocq.cyrano.spi.gen.ClientProxyFactory;

/** Hand-written stale generated client: its factory was generated for {@link LoaderApi}. */
public final class StaleApi$$CyranoClient {

    private StaleApi$$CyranoClient() {
    }

    public static final class Factory implements ClientProxyFactory {

        @Override
        public Class<?> clientInterface() {
            return LoaderApi.class;
        }

        @Override
        public ClientDescriptor descriptor() {
            throw new IllegalStateException("a stale factory must not be used");
        }

        @Override
        public Object newProxy(ClientInvoker invoker) {
            throw new IllegalStateException("a stale factory must not be used");
        }
    }
}
