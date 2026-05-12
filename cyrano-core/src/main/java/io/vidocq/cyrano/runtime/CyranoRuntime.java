/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cyrano.runtime;

/**
 * Métadonnées runtime exposées par {@code cyrano-core}. Placeholder à M0 — sera
 * étoffé au fil des milestones (CyranoRestClientBuilder à M1, etc.).
 */
public final class CyranoRuntime {

    private CyranoRuntime() {
        // utility — pas d'instanciation
    }

    /** Identifiant de l'implémentation runtime. */
    public static String implementationName() {
        return "cyrano-core";
    }
}

