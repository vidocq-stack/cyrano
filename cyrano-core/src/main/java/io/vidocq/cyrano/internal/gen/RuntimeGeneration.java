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
package io.vidocq.cyrano.internal.gen;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The clients Cyrano generates at run time because the build did not: an interface compiled without the annotation
 * processor ({@code cyrano-processor}), or one the processor left to the fallback. That fallback is kept, but it is
 * the exception: it costs startup time and has no ahead-of-time equivalent (native image, CDS). So each interface
 * served that way is announced by a framed warning, once per JVM.
 */
public final class RuntimeGeneration {

    private static final System.Logger LOG = System.getLogger(RuntimeGeneration.class.getName());

    private static final String RULE = "=".repeat(100);

    private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

    private static final AtomicReference<Consumer<String>> WARN = new AtomicReference<>(RuntimeGeneration::log);

    private RuntimeGeneration() {
    }

    /** A client generated at run time for {@code iface}: warns unless a warning already named it. */
    static void generated(Class<?> iface) {
        if (ANNOUNCED.add(iface.getName())) {
            WARN.get().accept(warning(iface.getName()));
        }
    }

    /** The warning, framed so that it stands out of the startup log. */
    static String warning(String interfaceName) {
        return "\n" + RULE + "\n"
                + "CYRANO — RUNTIME CODE GENERATION FALLBACK\n"
                + "The REST client of " + interfaceName + " is generated at run time instead of at build time.\n"
                + """
                This interface was compiled without the Cyrano annotation processor (cyrano-processor), or the
                processor left it to the fallback (see its compiler NOTE). This fallback is exceptional: it slows the
                startup and cannot run ahead of time (native image, CDS).
                Fix: add cyrano-processor to the annotation processor path of the module that declares it.
                """
                + RULE;
    }

    private static void log(String message) {
        LOG.log(System.Logger.Level.WARNING, message);
    }

    /** Routes the warnings to {@code sink}, and forgets what was announced; {@code null} restores the logger. */
    static void redirectForTests(Consumer<String> sink) {
        WARN.set(sink == null ? RuntimeGeneration::log : sink);
        ANNOUNCED.clear();
    }
}
