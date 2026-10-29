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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GenericTypesTest {

    interface Crud<T, K> {
        T get(K id);

        List<T> all();

        T[] page();

        Map<K, List<T>> byKey();

        List<T>[] batches();

        <N extends Number> N number();
    }

    interface Keyed<T> extends Crud<T, UUID> {
    }

    interface Users extends Keyed<String> {
        Integer count();
    }

    private static Type returnType(String name) throws Exception {
        return GenericTypes.returnType(Users.class, Users.class.getMethod(name));
    }

    @Test
    void aTypeVariableIsTheTypeTheClientInterfaceBindsItTo() throws Exception {
        assertEquals(String.class,
                GenericTypes.returnType(Users.class, Users.class.getMethod("get", Object.class)));
    }

    @Test
    void aTypeVariableInsideAParameterizedTypeIsReplaced() throws Exception {
        assertEquals("java.util.List<java.lang.String>", returnType("all").getTypeName());
        assertEquals("java.util.Map<java.util.UUID, java.util.List<java.lang.String>>",
                returnType("byKey").getTypeName());
        assertEquals(List.class, GenericTypes.rawType(returnType("all")));
    }

    @Test
    void anArrayOfATypeVariableBecomesAnArrayOfItsBinding() throws Exception {
        assertEquals(String[].class, returnType("page"));
    }

    @Test
    void anArrayOfAParameterizedTypeKeepsItsArguments() throws Exception {
        Type batches = returnType("batches");
        assertEquals("java.util.List<java.lang.String>[]", batches.getTypeName());
        assertEquals(List[].class, GenericTypes.rawType(batches));
        Type declared = Holder.class.getDeclaredField("batches").getGenericType();
        assertEquals(declared, batches);
        assertEquals(batches, declared);
        assertEquals(declared.hashCode(), batches.hashCode());
    }

    @Test
    void aSubstitutedTypeEqualsTheOneTheJdkReportsForTheSameArguments() throws Exception {
        Type substituted = returnType("all");
        Type declared = Holder.class.getDeclaredField("strings").getGenericType();
        assertEquals(declared, substituted);
        assertEquals(substituted, declared);
        assertEquals(declared.hashCode(), substituted.hashCode());
        assertEquals(declared.toString(), substituted.toString());
        assertNull(((java.lang.reflect.ParameterizedType) substituted).getOwnerType());
    }

    @Test
    void aMethodTypeVariableIsLeftAndErasesToItsBound() throws Exception {
        Type number = returnType("number");
        assertEquals("N", number.getTypeName());
        assertEquals(Number.class, GenericTypes.rawType(number));
    }

    @Test
    void aWildcardErasesToObject() throws Exception {
        Type wildcard = ((java.lang.reflect.ParameterizedType) Holder.class.getDeclaredField("anything")
                .getGenericType()).getActualTypeArguments()[0];
        assertEquals(Object.class, GenericTypes.rawType(wildcard));
    }

    static final class Holder {
        List<String> strings;
        List<?> anything;
        List<String>[] batches;
    }

    @Test
    void aMethodOfTheClientInterfaceItselfIsLeftAsDeclared() throws Exception {
        assertEquals(Integer.class, returnType("count"));
    }
}
