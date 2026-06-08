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

import java.lang.reflect.Field;
import java.util.List;

/**
 * Links a Java parameter to an HTTP location — sealed for completeness in
 * the {@code switch} resolution.
 *
 * <p>Covers MicroProfile Rest Client 4.0 §3.1 (Basic Parameter Types):
 * {@code @PathParam}, {@code @QueryParam}, {@code @HeaderParam}, {@code @CookieParam},
 * {@code @FormParam}, {@code @MatrixParam}, implicit body (unannotated parameter), and
 * {@code @BeanParam} (aggregation).</p>
 */
public sealed interface ParamBinding {

    int paramIndex();

    /** Default value applied if the argument is {@code null} ({@code @DefaultValue}). */
    String defaultValue();

    record Path(int paramIndex, String name, String defaultValue) implements ParamBinding {}
    record Query(int paramIndex, String name, String defaultValue) implements ParamBinding {}
    record Header(int paramIndex, String name, String defaultValue) implements ParamBinding {}
    record Cookie(int paramIndex, String name, String defaultValue) implements ParamBinding {}
    record Form(int paramIndex, String name, String defaultValue) implements ParamBinding {}
    record Matrix(int paramIndex, String name, String defaultValue) implements ParamBinding {}

    /** Unannotated parameter — request body (serialized via JSON-B according to {@code @Consumes}). */
    record Body(int paramIndex) implements ParamBinding {
        @Override public String defaultValue() { return null; }
    }

    /** {@code @BeanParam} aggregate — expands its sub-bindings into {@link FieldBinding}. */
    record Bean(int paramIndex, List<FieldBinding> fields) implements ParamBinding {
        public Bean {
            fields = List.copyOf(fields);
        }
        @Override public String defaultValue() { return null; }
    }

    /** Binding of a {@code @BeanParam} field to an HTTP location. */
    record FieldBinding(Field field, Kind kind, String name, String defaultValue) {
        public enum Kind { PATH, QUERY, HEADER, COOKIE, FORM, MATRIX }
    }
}
