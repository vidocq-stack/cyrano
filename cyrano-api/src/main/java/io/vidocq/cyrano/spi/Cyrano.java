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
package io.vidocq.cyrano.spi;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

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

    /**
     * Current Cyrano implementation version, filtered by the Maven build into a
     * same-module resource. Not a compile-time constant on purpose: consumers
     * always read the version of the artifact actually on their module path.
     */
    public static final String IMPLEMENTATION_VERSION = loadVersion();

    private Cyrano() {
        // utility — no instantiation
    }

    private static String loadVersion() {
        try (InputStream in = Cyrano.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }
}
