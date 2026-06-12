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
import io.vidocq.cyrano.spi.gen.ClientMethodDescriptor;
import io.vidocq.cyrano.spi.gen.ClientProxyFactory;

import java.util.List;
import java.util.Map;

/** Hand-written generated client for {@link LoaderApi}, discovered via ServiceLoader. */
public final class LoaderApi$$CyranoClient implements LoaderApi, java.io.Closeable {

    private static final ClientDescriptor DESCRIPTOR = new ClientDescriptor(LoaderApi.class, List.of(
            new ClientMethodDescriptor(
                    "ping",
                    List.of(),
                    "GET", "/loader",
                    List.of(),
                    List.of(), List.of(),
                    Map.of(), Map.of())));

    private final ClientInvoker invoker;

    public LoaderApi$$CyranoClient(ClientInvoker invoker) {
        this.invoker = invoker;
    }

    @Override
    public String ping() {
        Object[] args = new Object[0];
        try {
            return (String) this.invoker.invoke(this, 0, args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        this.invoker.markClosed();
    }

    public static final class Factory implements ClientProxyFactory {

        @Override
        public Class<?> clientInterface() {
            return LoaderApi.class;
        }

        @Override
        public ClientDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Object newProxy(ClientInvoker invoker) {
            return new LoaderApi$$CyranoClient(invoker);
        }
    }
}
