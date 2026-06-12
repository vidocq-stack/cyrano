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
import io.vidocq.cyrano.spi.gen.ClientParamDescriptor;
import io.vidocq.cyrano.spi.gen.ClientProxyFactory;

import java.util.List;
import java.util.Map;

/**
 * Hand-written replica of what {@code CyranoClientProcessor} emits for
 * {@link FixtureApi} — the reference template for the processor's output and the
 * fixture for {@link ClientProxyRegistryTest}'s naming-convention tier.
 */
public final class FixtureApi$$CyranoClient implements FixtureApi, java.io.Closeable {

    private static final ClientDescriptor DESCRIPTOR = new ClientDescriptor(FixtureApi.class, List.of(
            new ClientMethodDescriptor(
                    "get",
                    List.of(String.class),
                    "GET", "/fixture/{id}",
                    List.of(new ClientParamDescriptor.Path(0, "id", null)),
                    List.of(), List.of(),
                    Map.of(), Map.of()),
            new ClientMethodDescriptor(
                    "post",
                    List.of(String.class),
                    "POST", "/fixture",
                    List.of(new ClientParamDescriptor.Body(0)),
                    List.of(), List.of(),
                    Map.of(), Map.of())));

    private final ClientInvoker invoker;

    public FixtureApi$$CyranoClient(ClientInvoker invoker) {
        this.invoker = invoker;
    }

    @Override
    public String get(String id) {
        Object[] args = new Object[] { id };
        try {
            return (String) this.invoker.invoke(this, 0, args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long post(String body) {
        Object[] args = new Object[] { body };
        try {
            return (Long) this.invoker.invoke(this, 1, args);
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

    /** ServiceLoader-able factory — instantiated reflectively by the naming-convention tier. */
    public static final class Factory implements ClientProxyFactory {

        @Override
        public Class<?> clientInterface() {
            return FixtureApi.class;
        }

        @Override
        public ClientDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Object newProxy(ClientInvoker invoker) {
            return new FixtureApi$$CyranoClient(invoker);
        }
    }
}
