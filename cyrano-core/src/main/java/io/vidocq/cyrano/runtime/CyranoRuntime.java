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
 * Runtime metadata exposed by {@code cyrano-core}. Placeholder at M0 — will be
 * expanded along milestones (CyranoRestClientBuilder to M1, etc.).
 */
public final class CyranoRuntime {

    private CyranoRuntime() {
        //utility — no detailing
    }

    /** Runtime implementation identifier. */
    public static String implementationName() {
        return "cyrano-core";
    }
}
