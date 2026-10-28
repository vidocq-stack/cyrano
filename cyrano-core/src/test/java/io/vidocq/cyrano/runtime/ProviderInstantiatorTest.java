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
package io.vidocq.cyrano.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ProviderInstantiator}: without any declared implementation (pure SE mode), the
 * reflective default instantiator is used (MP Rest Client 4.0 §4.2.4 only requires the
 * BeanManager when CDI is present).
 */
class ProviderInstantiatorTest {

    static final class PrivateProvider {
        private PrivateProvider() {
            // reachable only reflectively, as a provider would be
        }
    }

    static final class NoDefaultConstructor {
        final String value;

        NoDefaultConstructor(String value) {
            this.value = value;
        }
    }

    @Test
    void current_falls_back_to_the_reflective_instantiator_when_none_is_declared() {
        Object created = ProviderInstantiator.current().create(PrivateProvider.class);
        assertInstanceOf(PrivateProvider.class, created);
    }

    @Test
    void default_instantiator_rejects_a_class_without_no_arg_constructor() {
        ProviderInstantiator instantiator = ProviderInstantiator.defaultInstantiator();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> instantiator.create(NoDefaultConstructor.class));
        assertInstanceOf(NoSuchMethodException.class, e.getCause());
    }

    @Test
    void default_around_invoke_is_transparent() throws Exception {
        ProviderInstantiator instantiator = ProviderInstantiator.defaultInstantiator();
        assertEquals("done", instantiator.aroundInvoke(this, null, new Object[0], () -> "done"));
    }
}
