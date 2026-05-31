/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
