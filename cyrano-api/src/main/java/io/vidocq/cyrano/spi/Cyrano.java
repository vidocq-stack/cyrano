/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cyrano.spi;

/**
 * Cyrano implementation metadata — constants accessible from TCK tests
 * and runtime integration.
 *
 * <p>For the MicroProfile Rest Client 4.0 spec, the implementation identifies itself to
 * the TCK and consumers through these constants.</p>
 */
public final class Cyrano {

    /** Short implementation identifier. */
    public static final String IMPLEMENTATION_NAME = "cyrano";

    /** MicroProfile Rest Client spec version implemented. */
    public static final String SPEC_VERSION = "4.0";

    /** Current Cyrano implementation version. */
    public static final String IMPLEMENTATION_VERSION = "0.1.0-SNAPSHOT";

    private Cyrano() {
        // utility — no instantiation
    }
}
