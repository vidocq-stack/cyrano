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
 * Métadonnées d'implémentation Cyrano — constantes accessibles depuis les tests TCK
 * et l'intégration runtime.
 *
 * <p>Pour la spec MicroProfile Rest Client 4.0, l'implémentation s'identifie auprès
 * du TCK et des consommateurs via ces constantes.</p>
 */
public final class Cyrano {

    /** Identifiant court de l'implémentation. */
    public static final String IMPLEMENTATION_NAME = "cyrano";

    /** Version de la spec MicroProfile Rest Client implémentée. */
    public static final String SPEC_VERSION = "4.0";

    /** Version courante de l'implémentation Cyrano. */
    public static final String IMPLEMENTATION_VERSION = "0.1.0-SNAPSHOT";

    private Cyrano() {
        // utility — pas d'instanciation
    }
}

