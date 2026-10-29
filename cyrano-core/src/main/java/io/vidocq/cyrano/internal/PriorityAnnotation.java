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
package io.vidocq.cyrano.internal;

import java.lang.annotation.Annotation;

/**
 * Reads {@code jakarta.annotation.Priority} on a provider class without depending on that API: the annotation is
 * matched by name among the class's annotations, so no class is loaded, and the API may be visible to the provider
 * alone (Cyrano does not read it).
 */
final class PriorityAnnotation {

    private static final String PRIORITY = "jakarta.annotation.Priority";

    private PriorityAnnotation() {
    }

    /** The {@code @Priority} value of {@code type}, else {@code fallback}. */
    static int of(Class<?> type, int fallback) {
        for (Annotation annotation : type.getAnnotations()) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType.getName().equals(PRIORITY)) {
                try {
                    return annotationType.getMethod("value").invoke(annotation) instanceof Integer value
                            ? value : fallback;
                } catch (ReflectiveOperationException _) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}
