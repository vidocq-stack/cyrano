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
package io.vidocq.cyrano.internal.gen;

import io.vidocq.cyrano.internal.ParamBinding;
import io.vidocq.cyrano.internal.RequestSpec;
import io.vidocq.cyrano.spi.gen.ClientDescriptor;
import io.vidocq.cyrano.spi.gen.ClientMethodDescriptor;
import io.vidocq.cyrano.spi.gen.ClientParamDescriptor;
import io.vidocq.cyrano.spi.gen.DynamicHeaderDescriptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the internal {@link RequestSpec} list from a compile-time
 * {@link ClientDescriptor} — the generated-path replacement for
 * {@link io.vidocq.cyrano.internal.CyranoInterfaceScanner}'s annotation scan.
 *
 * <p>The only reflection left is targeted resolution: one
 * {@link Class#getMethod(String, Class[])} per descriptor entry (the
 * {@code Method} is needed for return types and dynamic-header dispatch) and one
 * field lookup per {@code @BeanParam} field. No annotation is ever read.</p>
 *
 * <p>Output order follows descriptor order — the {@code methodIndex} contract
 * shared with the generated proxy.</p>
 */
public final class DescriptorConverter {

    private DescriptorConverter() {
        // utility
    }

    public static List<RequestSpec> convert(ClientDescriptor descriptor) {
        Class<?> iface = descriptor.clientInterface();
        List<RequestSpec> out = new ArrayList<>(descriptor.methods().size());
        for (ClientMethodDescriptor md : descriptor.methods()) {
            out.add(convertMethod(iface, md));
        }
        return List.copyOf(out);
    }

    private static RequestSpec convertMethod(Class<?> iface, ClientMethodDescriptor md) {
        Method method = resolveMethod(iface, md);
        List<ParamBinding> bindings = new ArrayList<>(md.params().size());
        for (ClientParamDescriptor pd : md.params()) {
            bindings.add(convertParam(iface, md, pd));
        }
        Map<String, RequestSpec.DynamicHeader> dynamicHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, DynamicHeaderDescriptor> e : md.dynamicHeaders().entrySet()) {
            dynamicHeaders.put(e.getKey(),
                    new RequestSpec.DynamicHeader(e.getValue().methodName(), e.getValue().required()));
        }
        return new RequestSpec(
                md.httpMethod(), md.pathTemplate(), bindings,
                method.getReturnType(), method.getGenericReturnType(),
                md.consumes(), md.produces(),
                md.staticHeaders(), dynamicHeaders,
                method);
    }

    private static Method resolveMethod(Class<?> iface, ClientMethodDescriptor md) {
        try {
            return iface.getMethod(md.methodName(), md.parameterTypes().toArray(new Class<?>[0]));
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "Descriptor method " + md.methodName() + md.parameterTypes()
                            + " not found on " + iface.getName()
                            + " — stale generated class? Rebuild with the Cyrano processor.", e);
        }
    }

    private static ParamBinding convertParam(Class<?> iface, ClientMethodDescriptor md,
                                             ClientParamDescriptor pd) {
        return switch (pd) {
            case ClientParamDescriptor.Path p -> new ParamBinding.Path(p.paramIndex(), p.name(), p.defaultValue());
            case ClientParamDescriptor.Query q -> new ParamBinding.Query(q.paramIndex(), q.name(), q.defaultValue());
            case ClientParamDescriptor.Header h -> new ParamBinding.Header(h.paramIndex(), h.name(), h.defaultValue());
            case ClientParamDescriptor.Cookie c -> new ParamBinding.Cookie(c.paramIndex(), c.name(), c.defaultValue());
            case ClientParamDescriptor.Form f -> new ParamBinding.Form(f.paramIndex(), f.name(), f.defaultValue());
            case ClientParamDescriptor.Matrix m -> new ParamBinding.Matrix(m.paramIndex(), m.name(), m.defaultValue());
            case ClientParamDescriptor.Body b -> new ParamBinding.Body(b.paramIndex());
            case ClientParamDescriptor.Bean bean -> convertBean(iface, md, bean);
        };
    }

    private static ParamBinding convertBean(Class<?> iface, ClientMethodDescriptor md,
                                            ClientParamDescriptor.Bean bean) {
        List<ParamBinding.FieldBinding> fields = new ArrayList<>(bean.fields().size());
        for (ClientParamDescriptor.BeanField bf : bean.fields()) {
            Field field = resolveField(iface, md, bean.beanType(), bf.fieldName());
            fields.add(new ParamBinding.FieldBinding(field, convertKind(bf.kind()), bf.name(), bf.defaultValue()));
        }
        return new ParamBinding.Bean(bean.paramIndex(), fields);
    }

    private static Field resolveField(Class<?> iface, ClientMethodDescriptor md,
                                      Class<?> beanType, String fieldName) {
        Class<?> c = beanType;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getName().equals(fieldName)) continue;
                // Same accessibility contract as CyranoInterfaceScanner#fb — bean POJOs
                // are expected to be open to the framework (M2 trade-off).
                try {
                    f.setAccessible(true);
                } catch (RuntimeException ignored) {
                    // field already accessible
                }
                return f;
            }
            c = c.getSuperclass();
        }
        throw new IllegalArgumentException(
                "Descriptor @BeanParam field '" + fieldName + "' not found on "
                        + beanType.getName() + " (method " + md.methodName()
                        + " of " + iface.getName() + ") — stale generated class?");
    }

    private static ParamBinding.FieldBinding.Kind convertKind(ClientParamDescriptor.BeanField.Kind kind) {
        return switch (kind) {
            case PATH -> ParamBinding.FieldBinding.Kind.PATH;
            case QUERY -> ParamBinding.FieldBinding.Kind.QUERY;
            case HEADER -> ParamBinding.FieldBinding.Kind.HEADER;
            case COOKIE -> ParamBinding.FieldBinding.Kind.COOKIE;
            case FORM -> ParamBinding.FieldBinding.Kind.FORM;
            case MATRIX -> ParamBinding.FieldBinding.Kind.MATRIX;
        };
    }
}
