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
 * Dynamic header {@code @ClientHeaderParam(value = "{methodName}")} —
 * MP Rest Client 4.0 §6.5.
 *
 * @param methodName name of the {@code default}/{@code static} compute method
 * @param required   {@code true} (default) propagates compute failures;
 *                   {@code false} silently omits the header on failure
 * @param compute    generated call of the compute method, or {@code null} when the generated class cannot reach it
 *                   (a private method): the method is then found and invoked by reflection. Not part of equality.
 */
public record DynamicHeaderDescriptor(String methodName, boolean required, Compute compute) {

    /** A descriptor without generated call, as classes generated before it existed build it. */
    public DynamicHeaderDescriptor(String methodName, boolean required) {
        this(methodName, required, null);
    }

    /** The generated call of a compute method: the proxy for a {@code default} method, then the header name. */
    @FunctionalInterface
    public interface Compute {
        /** @return the header value, a {@code String} or a {@code String[]} */
        Object compute(Object proxy, String headerName) throws Exception;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DynamicHeaderDescriptor that
                && methodName.equals(that.methodName) && required == that.required;
    }

    @Override
    public int hashCode() {
        return 31 * methodName.hashCode() + Boolean.hashCode(required);
    }

    @Override
    public String toString() {
        return "DynamicHeaderDescriptor[methodName=" + methodName + ", required=" + required + "]";
    }
}
