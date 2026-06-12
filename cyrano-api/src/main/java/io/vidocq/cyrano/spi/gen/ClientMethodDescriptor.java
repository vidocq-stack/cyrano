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

import java.util.List;
import java.util.Map;

/**
 * Literal description of one client interface method — everything cyrano-core needs
 * to rebuild its internal request spec without scanning annotations at runtime.
 *
 * <p>The position of this descriptor in {@link ClientDescriptor#methods()} defines the
 * {@code methodIndex} passed by the generated proxy to
 * {@link ClientInvoker#invoke(Object, int, Object[])}.</p>
 *
 * @param methodName     name of the interface method
 * @param parameterTypes erased parameter types, used to resolve the {@code Method}
 * @param httpMethod     uppercase HTTP verb, or {@code null} for a sub-resource
 *                       locator ({@code @Path} without verb returning an interface)
 * @param pathTemplate   full path template (interface base path already joined)
 * @param params         parameter bindings, one per method parameter
 * @param consumes       {@code @Consumes} media types (method overrides type level)
 * @param produces       {@code @Produces} media types (method overrides type level)
 * @param staticHeaders  fixed headers via {@code @ClientHeaderParam} (spec §6.5)
 * @param dynamicHeaders computed headers via {@code @ClientHeaderParam("{method}")}
 */
public record ClientMethodDescriptor(
        String methodName,
        List<Class<?>> parameterTypes,
        String httpMethod,
        String pathTemplate,
        List<ClientParamDescriptor> params,
        List<String> consumes,
        List<String> produces,
        Map<String, List<String>> staticHeaders,
        Map<String, DynamicHeaderDescriptor> dynamicHeaders
) {
    public ClientMethodDescriptor {
        parameterTypes = List.copyOf(parameterTypes);
        params = List.copyOf(params);
        consumes = List.copyOf(consumes);
        produces = List.copyOf(produces);
        staticHeaders = Map.copyOf(staticHeaders);
        dynamicHeaders = Map.copyOf(dynamicHeaders);
    }
}
