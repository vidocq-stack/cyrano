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
package io.vidocq.cyrano.spi.gen;

/**
 * SPI implemented by the {@code Factory} nested in each generated
 * {@code $$CyranoClient} class. cyrano-core discovers factories in order of
 * preference: {@code ServiceLoader} (JPMS-friendly, lets the user keep the client
 * package fully encapsulated via {@code provides ... with}), then by naming
 * convention ({@code Class.forName(iface.getName() + "$$CyranoClient")}), and only
 * then falls back to runtime proxy generation.
 *
 * <p>Implementations must be stateless with a public no-arg constructor.</p>
 */
public interface ClientProxyFactory {

    /** The client interface this factory builds proxies for. */
    Class<?> clientInterface();

    /** The compile-time descriptor of {@link #clientInterface()}. */
    ClientDescriptor descriptor();

    /**
     * Instantiates the generated proxy bound to {@code invoker}.
     *
     * @return an instance of {@link #clientInterface()} (also {@code java.io.Closeable})
     */
    Object newProxy(ClientInvoker invoker);
}
