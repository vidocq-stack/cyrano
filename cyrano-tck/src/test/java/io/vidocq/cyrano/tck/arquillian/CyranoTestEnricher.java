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
 * Arquillian {@link TestEnricher} — injects the {@code @Inject} fields of each
 * TCK test instance using the Vauban container started by
 * {@link VaubanTckBootstrap}.
 *
 * <p>Supports:</p>
 * <ul>
 * <li>{@code @Inject BeanManager} — direct resolution from the Vauban container;</li>
 * <li>{@code @Inject @RestClient SomeApi} — resolution via the standard CDI
 *       {@link BeanManager#getBeans(java.lang.reflect.Type, Annotation...)},
 *       passing the qualifiers carried by the field.</li>
 * </ul>
 *
 * <p>Registration: {@link CyranoArquillianExtension#register(LoadableExtension.ExtensionBuilder)}
 * via {@code builder.service(TestEnricher.class, CyranoTestEnricher.class)}.</p>
 *
 * <p>Spec MP Rest Client 4.0 §6.2 — « The container must use the RestClientBuilder API
 * to instantiate the rest client interface proxy » — satisfied via
 * {@code CyranoRestClientSyntheticCreator}; this {@code TestEnricher} simply wires the
 * fields of the test instance outside of the TestNG container.</p>
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
                //Not solvent — skip (test may fail for its own reasons)
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

        // Explicit support for @Inject @RestClient Instance<T> (TCK JsonBProviderTest).
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
     * Collect all the annotations of the field, excluding {@code @Inject} itself,
     * to transmit them as CDI qualifiers to {@link BeanManager}.
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
     * Move the hierarchy of classes to collect all declared fields
     * (including those inherited from test superclasses).
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
