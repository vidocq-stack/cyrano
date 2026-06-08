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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.AnnotationLiteral;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Producer for @RestClient Instance<T>.
 *
 * <p>The TCK explicitly requires this injection form; we map it to an
 * explicit CDI selection of T + @RestClient.</p>
 */
@Dependent
public class CyranoRestClientInstanceProducer {

    private static final Annotation REST_CLIENT = new AnnotationLiteral<RestClient>() {};

    @Produces
    @RestClient
    @Dependent
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> Instance<T> produce(InjectionPoint injectionPoint) {
        Class<T> targetType = (Class<T>) extractTargetType(injectionPoint);
        return (Instance<T>) CDI.current().select((Class) targetType, REST_CLIENT);
    }

    private static Class<?> extractTargetType(InjectionPoint ip) {
        Type type = ip == null ? null : ip.getType();
        if (type instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1) {
            Type arg = pt.getActualTypeArguments()[0];
            if (arg instanceof Class<?> c) {
                return c;
            }
        }
        throw new UnsatisfiedResolutionException("@RestClient Instance<T> must be parameterized with a client interface");
    }
}

