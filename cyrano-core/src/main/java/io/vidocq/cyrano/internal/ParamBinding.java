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
 * Liaison d'un paramètre Java à un emplacement HTTP — sealed pour exhaustivité dans
 * le {@code switch} de résolution.
 *
 * <p>Couvre la spec MicroProfile Rest Client 4.0 §3.1 (types de paramètre de base) :
 * {@code @PathParam}, {@code @QueryParam}, {@code @HeaderParam}, {@code @CookieParam},
 * {@code @FormParam}, {@code @MatrixParam}, body implicite (paramètre non annoté), et
 * {@code @BeanParam} (agrégation).</p>
 */
public sealed interface ParamBinding {

    int paramIndex();

    /** Valeur par défaut appliquée si l'argument est {@code null} ({@code @DefaultValue}). */
    String defaultValue();

    record Path(int paramIndex, String name, String defaultValue) implements ParamBinding {}
    record Query(int paramIndex, String name, String defaultValue) implements ParamBinding {}
    record Header(int paramIndex, String name, String defaultValue) implements ParamBinding {}
    record Cookie(int paramIndex, String name, String defaultValue) implements ParamBinding {}
    record Form(int paramIndex, String name, String defaultValue) implements ParamBinding {}
    record Matrix(int paramIndex, String name, String defaultValue) implements ParamBinding {}

    /** Paramètre non annoté — corps de la requête (sérialisé via JSON-B selon {@code @Consumes}). */
    record Body(int paramIndex) implements ParamBinding {
        @Override public String defaultValue() { return null; }
    }

    /** Agrégat {@code @BeanParam} — éclate ses sous-bindings en {@link FieldBinding}. */
    record Bean(int paramIndex, List<FieldBinding> fields) implements ParamBinding {
        public Bean {
            fields = List.copyOf(fields);
        }
        @Override public String defaultValue() { return null; }
    }

    /** Liaison d'un champ {@code @BeanParam} vers un emplacement HTTP. */
    record FieldBinding(Field field, Kind kind, String name, String defaultValue) {
        public enum Kind { PATH, QUERY, HEADER, COOKIE, FORM, MATRIX }
    }
}
