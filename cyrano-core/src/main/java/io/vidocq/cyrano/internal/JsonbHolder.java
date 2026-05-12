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

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

/**
 * Holder lazy + thread-safe d'une instance {@link Jsonb} partagée par toutes les
 * invocations Cyrano. {@code JsonbBuilder.create()} charge l'implémentation via
 * {@link java.util.ServiceLoader ServiceLoader} — champollion en runtime.
 *
 * <p>Pas de {@code synchronized} : initialisation par holder pattern (lazy class init
 * garantie thread-safe par la JVM).</p>
 */
final class JsonbHolder {

    private JsonbHolder() {}

    static Jsonb get() {
        return Lazy.INSTANCE;
    }

    private static final class Lazy {
        static final Jsonb INSTANCE = JsonbBuilder.create();
    }
}

