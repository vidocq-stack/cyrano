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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import org.junit.jupiter.api.Test;

/** The generated calls of compute methods and reads of bean fields give what the reflective ones give. */
class CyranoGeneratedAccessTest {

    static class Bean {
        String visible = "generated";
        String reflective = "reflective";
    }

    private static String compute(Object value) {
        return CyranoInvocationHandler.computeHeader(
                new RequestSpec.DynamicHeader("m", true, (proxy, header) -> value), "X-H", null);
    }

    @Test
    void aComputedValueIsJoinedOrPrintedAsTheReflectiveCallDoes() {
        assertEquals("a,b", compute(new String[] {"a", "b"}));
        assertEquals("v", compute("v"));
        assertEquals("42", compute(42));
        assertNull(compute(null));
    }

    @Test
    void failuresReachTheCallerAsTheReflectiveCallGivesThem() {
        IllegalArgumentException runtime = new IllegalArgumentException("boom");
        var failing = new RequestSpec.DynamicHeader("m", true, (p, h) -> { throw runtime; });
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> CyranoInvocationHandler.computeHeader(failing, "X-H", null));
        assertSame(runtime, thrown);

        IOException checked = new IOException("io");
        var failingChecked = new RequestSpec.DynamicHeader("m", true,
                (p, h) -> { throw new UndeclaredThrowableException(checked); });
        var wrapped = assertThrows(IllegalStateException.class,
                () -> CyranoInvocationHandler.computeHeader(failingChecked, "X-H", null));
        assertSame(checked, wrapped.getCause());
    }

    @Test
    void aFieldIsReadByTheGeneratedCodeOrElseByReflection() throws Exception {
        Bean bean = new Bean();
        var generated = new ParamBinding.FieldBinding(null, ParamBinding.FieldBinding.Kind.QUERY, "v", null,
                b -> ((Bean) b).visible);
        assertEquals("generated", CyranoInvocationHandler.fieldValue(generated, bean));

        var field = Bean.class.getDeclaredField("reflective");
        field.setAccessible(true);
        var reflective = new ParamBinding.FieldBinding(field, ParamBinding.FieldBinding.Kind.QUERY, "h", null);
        assertEquals("reflective", CyranoInvocationHandler.fieldValue(reflective, bean));
    }
}
