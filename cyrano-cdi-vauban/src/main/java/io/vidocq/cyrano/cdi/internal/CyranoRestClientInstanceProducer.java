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

