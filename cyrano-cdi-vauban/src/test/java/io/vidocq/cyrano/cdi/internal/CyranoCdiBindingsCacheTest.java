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
package io.vidocq.cyrano.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** The interceptor bindings of a client call are read once per instantiator, not on every call. */
class CyranoCdiBindingsCacheTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface Audited {}

    @Audited
    interface Api {
        String call();
    }

    @Test
    void theBindingsOfAClientMethodAreReadOnce() throws Exception {
        CyranoCdiProviderInstantiator instantiator = new CyranoCdiProviderInstantiator();
        Method call = Api.class.getMethod("call");

        Annotation[] first = instantiator.bindingsFor(Api.class, call);
        Annotation[] second = instantiator.bindingsFor(Api.class, call);

        assertSame(first, second);
        assertEquals(Audited.class, first[0].annotationType());
    }
}
