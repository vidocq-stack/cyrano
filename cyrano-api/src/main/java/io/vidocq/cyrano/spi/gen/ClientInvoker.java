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
 * Invocation contract between a generated client proxy and the Cyrano runtime.
 *
 * <p>Implemented internally by cyrano-core; generated {@code $$CyranoClient}
 * classes only compile against this interface (never against
 * {@code io.vidocq.cyrano.internal.*}).</p>
 */
public interface ClientInvoker {

    /**
     * Executes the HTTP request bound to {@code methodIndex}.
     *
     * @param proxy       the proxy instance itself — used to invoke
     *                    {@code default} compute methods for dynamic headers
     * @param methodIndex index of the method in the descriptor order
     * @param args        boxed invocation arguments (never {@code null})
     * @return the deserialized return value
     * @throws Exception any transport, mapping or user-provider failure
     */
    Object invoke(Object proxy, int methodIndex, Object[] args) throws Exception;

    /**
     * Marks the client as closed — subsequent {@link #invoke} calls must fail
     * with {@code IllegalStateException} (MP Rest Client 4.0 §8.1).
     */
    void markClosed();
}
