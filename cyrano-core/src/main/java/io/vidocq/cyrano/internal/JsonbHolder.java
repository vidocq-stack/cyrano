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
 * Lazy + thread-safe holder of a {@link Jsonb} instance shared by all Cyrano
 * invocations. {@code JsonbBuilder.create()} loads the implementation via
 * {@link java.util.ServiceLoader ServiceLoader} — champollion at runtime.
 *
 * <p>No {@code synchronized}: initialization via the holder pattern (lazy class
 * init is guaranteed thread-safe by the JVM).</p>
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

