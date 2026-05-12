/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.vidocq.cyrano.tck.arquillian;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import org.jboss.arquillian.test.spi.TestEnricher;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Arquillian {@link TestEnricher} — injecte les champs {@code @Inject} de chaque
 * instance de test TCK en utilisant le container Vauban démarré par
 * {@link VaubanTckBootstrap}.
 *
 * <p>Supporte :</p>
 * <ul>
 *   <li>{@code @Inject BeanManager} — résolution directe depuis le container Vauban ;</li>
 *   <li>{@code @Inject @RestClient SomeApi} — résolution via le
 *       {@link BeanManager#getBeans(java.lang.reflect.Type, Annotation...)} standard CDI,
 *       en passant les qualifieurs portés par le champ.</li>
 * </ul>
 *
 * <p>Enregistrement : {@link CyranoArquillianExtension#register(LoadableExtension.ExtensionBuilder)}
 * via {@code builder.service(TestEnricher.class, CyranoTestEnricher.class)}.</p>
 *
 * <p>Spec MP Rest Client 4.0 §6.2 — « The container must use the RestClientBuilder API
 * to instantiate the rest client interface proxy » — satisfait via
 * {@code CyranoRestClientSyntheticCreator} ; ce TestEnricher câble simplement les
 * champs de l'instance de test hors du container TestNG.</p>
 */
public class CyranoTestEnricher implements TestEnricher {

    @Override
    public void enrich(Object testInstance) {
        VaubanContainer container = VaubanContainer.current();
        if (container == null) return;

        BeanManager bm = container.getBeanManager();
        for (Field field : getAllFields(testInstance.getClass())) {
            if (!field.isAnnotationPresent(Inject.class)) continue;
            try {
                injectField(testInstance, field, bm);
            } catch (Exception ignored) {
                // Not resolvable — skip (test may fail for its own reasons)
            }
        }
    }

    @Override
    public Object[] resolve(Method method) {
        return new Object[0];
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void injectField(Object testInstance, Field field, BeanManager bm) throws Exception {
        var qualifiers = extractQualifiers(field);
        var type = field.getType();

        // Support explicite de @Inject @RestClient Instance<T> (TCK JsonBProviderTest).
        if (jakarta.enterprise.inject.Instance.class.equals(type)) {
            Class<?> targetType = extractInstanceTargetType(field.getGenericType());
            if (targetType != null) {
                @SuppressWarnings("unchecked")
                Object selected = bm.createInstance().select(targetType, qualifiers.toArray(new Annotation[0]));
                field.setAccessible(true);
                field.set(testInstance, selected);
                return;
            }
        }

        @SuppressWarnings("unchecked")
        var beans = bm.getBeans(type, qualifiers.toArray(new Annotation[0]));
        if (beans == null || beans.isEmpty()) return;

        var resolved = bm.resolve(beans);
        if (resolved == null) return;

        var ctx = bm.createCreationalContext(resolved);
        @SuppressWarnings("unchecked")
        var instance = bm.getReference(resolved, type, ctx);
        if (instance == null) return;

        field.setAccessible(true);
        field.set(testInstance, instance);
    }

    private static Class<?> extractInstanceTargetType(Type genericType) {
        if (!(genericType instanceof ParameterizedType pt)) return null;
        if (pt.getActualTypeArguments().length != 1) return null;
        Type arg = pt.getActualTypeArguments()[0];
        return (arg instanceof Class<?> c) ? c : null;
    }

    /**
     * Collecte toutes les annotations du champ, en excluant {@code @Inject} lui-même,
     * pour les transmettre comme qualifieurs CDI au {@link BeanManager}.
     */
    private static List<Annotation> extractQualifiers(Field field) {
        var qualifiers = new ArrayList<Annotation>();
        for (Annotation ann : field.getAnnotations()) {
            if (ann.annotationType().equals(Inject.class)) continue;
            qualifiers.add(ann);
        }
        return qualifiers;
    }

    /**
     * Remonte la hiérarchie de classes pour collecter tous les champs déclarés
     * (y compris ceux hérités des superclasses de test).
     */
    private static List<Field> getAllFields(Class<?> clazz) {
        var fields = new ArrayList<Field>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                fields.add(f);
            }
            current = current.getSuperclass();
        }
        return fields;
    }
}
