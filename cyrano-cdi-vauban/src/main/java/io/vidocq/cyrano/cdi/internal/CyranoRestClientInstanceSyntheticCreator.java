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
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.AnnotationLiteral;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Iterator;

/**
 * Synthetic bean for {@code @RestClient Instance<T>}.
 *
 * <p>Vauban does not resolve this case correctly for some TCK tests (backed by EventImpl).
 * The injected instance explicitly re-runs a CDI selection of type {@code T} qualified
 * with {@code @RestClient}.</p>
 */
public final class CyranoRestClientInstanceSyntheticCreator implements SyntheticBeanCreator<Object> {

    @Override
    public Object create(Instance<Object> lookup, Parameters params) {
        InjectionPoint ip = CDI.current().select(InjectionPoint.class).get();
        Class<?> targetType = extractTargetType(ip);
        return new RestClientInstanceAdapter(targetType);
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

    private static final class RestClientInstanceAdapter implements Instance<Object> {
        private static final Annotation REST_CLIENT = new AnnotationLiteral<RestClient>() {};
        private final Class<?> targetType;

        private RestClientInstanceAdapter(Class<?> targetType) {
            this.targetType = targetType;
        }

        @SuppressWarnings("rawtypes")
        private Instance selected() {
            return CDI.current().select(targetType, REST_CLIENT);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object get() {
            return selected().get();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Instance<Object> select(Annotation... qualifiers) {
            return (Instance<Object>) CDI.current().select((Class) targetType, qualifiers);
        }

        @Override
        public <U> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            return CDI.current().select(subtype, qualifiers);
        }

        @Override
        public <U> Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype, Annotation... qualifiers) {
            return CDI.current().select(subtype, qualifiers);
        }

        @Override
        public boolean isUnsatisfied() {
            return selected().isUnsatisfied();
        }

        @Override
        public boolean isAmbiguous() {
            return selected().isAmbiguous();
        }

        @Override
        public boolean isResolvable() {
            return selected().isResolvable();
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public void destroy(Object instance) {
            selected().destroy(instance);
        }

        @Override
        @SuppressWarnings("unchecked")
        public jakarta.enterprise.inject.Instance.Handle<Object> getHandle() {
            return selected().getHandle();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Iterable<? extends jakarta.enterprise.inject.Instance.Handle<Object>> handles() {
            return selected().handles();
        }

        @Override
        @SuppressWarnings("unchecked")
        public java.util.stream.Stream<? extends jakarta.enterprise.inject.Instance.Handle<Object>> handlesStream() {
            return selected().handlesStream();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Iterator<Object> iterator() {
            return selected().iterator();
        }
    }
}



