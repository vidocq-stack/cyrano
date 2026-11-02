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

/**
 * Literal, reflection-free binding of a Java parameter to an HTTP location —
 * the compile-time mirror of cyrano-core's internal {@code ParamBinding}.
 * Covers MP Rest Client 4.0 §3.1: {@code @PathParam}, {@code @QueryParam},
 * {@code @HeaderParam}, {@code @CookieParam}, {@code @FormParam},
 * {@code @MatrixParam}, implicit body, and {@code @BeanParam}.
 */
public sealed interface ClientParamDescriptor {

    /** Zero-based index of the parameter on the client method. */
    int paramIndex();

    /** Default value applied when the argument is {@code null} ({@code @DefaultValue}). */
    String defaultValue();

    record Path(int paramIndex, String name, String defaultValue) implements ClientParamDescriptor {}

    record Query(int paramIndex, String name, String defaultValue) implements ClientParamDescriptor {}

    record Header(int paramIndex, String name, String defaultValue) implements ClientParamDescriptor {}

    record Cookie(int paramIndex, String name, String defaultValue) implements ClientParamDescriptor {}

    record Form(int paramIndex, String name, String defaultValue) implements ClientParamDescriptor {}

    record Matrix(int paramIndex, String name, String defaultValue) implements ClientParamDescriptor {}

    /** Unannotated parameter — the request body (spec §3.1, one body per method). */
    record Body(int paramIndex) implements ClientParamDescriptor {
        @Override
        public String defaultValue() {
            return null;
        }
    }

    /** {@code @BeanParam} aggregate — its annotated fields, in hierarchy order. */
    record Bean(int paramIndex, Class<?> beanType, List<BeanField> fields) implements ClientParamDescriptor {
        public Bean {
            fields = List.copyOf(fields);
        }

        @Override
        public String defaultValue() {
            return null;
        }
    }

    /**
     * Binding of one {@code @BeanParam} field. {@code accessor} is the generated read of the field; it is
     * {@code null} when the generated class cannot reach it (a private field), and cyrano-core then resolves the
     * {@code java.lang.reflect.Field} from {@link Bean#beanType()}. The accessor is not part of equality.
     */
    record BeanField(String fieldName, Kind kind, String name, String defaultValue,
                     java.util.function.Function<Object, Object> accessor) {
        public enum Kind { PATH, QUERY, HEADER, COOKIE, FORM, MATRIX }

        /** A field without generated read, as classes generated before it existed build it. */
        public BeanField(String fieldName, Kind kind, String name, String defaultValue) {
            this(fieldName, kind, name, defaultValue, null);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BeanField that && fieldName.equals(that.fieldName) && kind == that.kind
                    && java.util.Objects.equals(name, that.name)
                    && java.util.Objects.equals(defaultValue, that.defaultValue);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(fieldName, kind, name, defaultValue);
        }

        @Override
        public String toString() {
            return "BeanField[fieldName=" + fieldName + ", kind=" + kind + ", name=" + name
                    + ", defaultValue=" + defaultValue + "]";
        }
    }
}
