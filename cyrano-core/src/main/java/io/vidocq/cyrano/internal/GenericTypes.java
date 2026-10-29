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
package io.vidocq.cyrano.internal;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The return type of a client method as the client interface sees it. A method inherited from a generic
 * super-interface ({@code interface EchoClient extends Echo<String>}) declares {@code T get()}: reflection reports
 * {@code T} and its erasure {@code Object}, while the response must be read as the {@code String} the client
 * interface binds {@code T} to.
 */
public final class GenericTypes {

    private GenericTypes() {
    }

    /** The generic return type of {@code method}, its type variables replaced by what {@code iface} binds them to. */
    public static Type returnType(Class<?> iface, Method method) {
        Type type = method.getGenericReturnType();
        if (method.getDeclaringClass() == iface || method.getDeclaringClass().getTypeParameters().length == 0) {
            return type;
        }
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        collect(iface, bindings);
        return bindings.isEmpty() ? type : substitute(type, bindings);
    }

    /** The raw class of {@code type}: what a resolved type variable erases to. */
    public static Class<?> rawType(Type type) {
        return switch (type) {
            case Class<?> c -> c;
            case ParameterizedType p -> (Class<?>) p.getRawType();
            case GenericArrayType a -> rawType(a.getGenericComponentType()).arrayType();
            case TypeVariable<?> v -> v.getBounds().length == 0 ? Object.class : rawType(v.getBounds()[0]);
            default -> Object.class;
        };
    }

    /** The bindings {@code type} gives to the type parameters of its super-interfaces, transitively. */
    private static void collect(Class<?> type, Map<TypeVariable<?>, Type> bindings) {
        for (Type sup : type.getGenericInterfaces()) {
            if (sup instanceof ParameterizedType p) {
                TypeVariable<?>[] params = ((Class<?>) p.getRawType()).getTypeParameters();
                Type[] args = p.getActualTypeArguments();
                for (int i = 0; i < params.length; i++) {
                    bindings.putIfAbsent(params[i], substitute(args[i], bindings));
                }
            }
            collect(rawType(sup), bindings);
        }
    }

    private static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings) {
        return switch (type) {
            case TypeVariable<?> v -> bindings.getOrDefault(v, v);
            case ParameterizedType p -> {
                Type[] args = p.getActualTypeArguments().clone();
                boolean changed = false;
                for (int i = 0; i < args.length; i++) {
                    Type arg = substitute(args[i], bindings);
                    changed |= arg != args[i];
                    args[i] = arg;
                }
                yield changed ? new Parameterized((Class<?>) p.getRawType(), p.getOwnerType(), args) : p;
            }
            case GenericArrayType a -> {
                Type component = substitute(a.getGenericComponentType(), bindings);
                yield component == a.getGenericComponentType() ? a
                        : component instanceof Class<?> c ? c.arrayType() : (GenericArrayType) () -> component;
            }
            default -> type;
        };
    }

    /** A parameterized type whose arguments were substituted; equal to the JDK's own for the same arguments. */
    private record Parameterized(Class<?> raw, Type owner, Type[] args) implements ParameterizedType {

        @Override
        public Type[] getActualTypeArguments() {
            return args.clone();
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public Type getOwnerType() {
            return owner;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ParameterizedType p && raw.equals(p.getRawType())
                    && Objects.equals(owner, p.getOwnerType()) && Arrays.equals(args, p.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(args) ^ Objects.hashCode(owner) ^ raw.hashCode();
        }

        @Override
        public String toString() {
            return getTypeName();
        }

        @Override
        public String getTypeName() {
            StringBuilder sb = new StringBuilder(raw.getTypeName()).append('<');
            for (int i = 0; i < args.length; i++) {
                sb.append(i > 0 ? ", " : "").append(args[i].getTypeName());
            }
            return sb.append('>').toString();
        }
    }
}
