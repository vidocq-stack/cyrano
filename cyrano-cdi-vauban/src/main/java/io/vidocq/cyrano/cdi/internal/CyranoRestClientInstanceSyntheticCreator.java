/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
 * Bean synthétique pour @RestClient Instance<T>.
 *
 * <p>Vauban ne résout pas correctement ce cas pour certains tests TCK (retour EventImpl).
 * On injecte une Instance qui relance explicitement une sélection CDI de type T + @RestClient.</p>
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
        throw new UnsatisfiedResolutionException("@RestClient Instance<T> doit etre parametre avec une interface client");
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



