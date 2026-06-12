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
package io.vidocq.cyrano.spi.gen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Invariants of the {@code spi.gen} descriptor records — the literal, reflection-free
 * metadata emitted by the Cyrano annotation processor and consumed by cyrano-core to
 * rebuild request specs without scanning annotations at runtime.
 *
 * <p>The records must be deeply immutable (defensive copies) so a generated
 * {@code ClientProxyFactory} can expose a shared singleton descriptor.</p>
 */
class ClientDescriptorTest {

    interface SampleApi {
        String get(String id);
    }

    private static ClientMethodDescriptor sampleMethod() {
        return new ClientMethodDescriptor(
                "get",
                List.of(String.class),
                "GET",
                "/api/{id}",
                List.of(new ClientParamDescriptor.Path(0, "id", null)),
                List.of("application/json"),
                List.of("application/json"),
                Map.of("X-Static", List.of("v1")),
                Map.of("X-Dyn", new DynamicHeaderDescriptor("computeHeader", true)));
    }

    @Test
    void clientDescriptor_copiesMethodList() {
        var methods = new ArrayList<ClientMethodDescriptor>();
        methods.add(sampleMethod());
        var descriptor = new ClientDescriptor(SampleApi.class, methods);
        methods.clear();
        assertEquals(1, descriptor.methods().size());
        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.methods().add(sampleMethod()));
    }

    @Test
    void methodDescriptor_copiesAllCollections() {
        var paramTypes = new ArrayList<Class<?>>(List.of(String.class));
        var params = new ArrayList<ClientParamDescriptor>(List.of(new ClientParamDescriptor.Body(0)));
        var consumes = new ArrayList<>(List.of("application/json"));
        var produces = new ArrayList<>(List.of("text/plain"));
        var staticHeaders = new HashMap<String, List<String>>(Map.of("A", List.of("1")));
        var dynamicHeaders = new HashMap<String, DynamicHeaderDescriptor>(
                Map.of("B", new DynamicHeaderDescriptor("m", false)));

        var d = new ClientMethodDescriptor("post", paramTypes, "POST", "/", params,
                consumes, produces, staticHeaders, dynamicHeaders);

        paramTypes.clear();
        params.clear();
        consumes.clear();
        produces.clear();
        staticHeaders.clear();
        dynamicHeaders.clear();

        assertEquals(List.of(String.class), d.parameterTypes());
        assertEquals(1, d.params().size());
        assertEquals(List.of("application/json"), d.consumes());
        assertEquals(List.of("text/plain"), d.produces());
        assertEquals(Map.of("A", List.of("1")), d.staticHeaders());
        assertEquals("m", d.dynamicHeaders().get("B").methodName());
    }

    @Test
    void methodDescriptor_subResourceLocatorHasNullHttpMethod() {
        var d = new ClientMethodDescriptor("sub", List.of(), null, "/sub", List.of(),
                List.of(), List.of(), Map.of(), Map.of());
        assertNull(d.httpMethod());
    }

    @Test
    void paramDescriptors_bodyAndBeanHaveNullDefaultValue() {
        assertNull(new ClientParamDescriptor.Body(2).defaultValue());
        assertNull(new ClientParamDescriptor.Bean(1, Object.class, List.of()).defaultValue());
    }

    @Test
    void beanParam_copiesFieldList() {
        var fields = new ArrayList<ClientParamDescriptor.BeanField>();
        fields.add(new ClientParamDescriptor.BeanField(
                "name", ClientParamDescriptor.BeanField.Kind.QUERY, "q", null));
        var bean = new ClientParamDescriptor.Bean(0, Object.class, fields);
        fields.clear();
        assertEquals(1, bean.fields().size());
        assertEquals("q", bean.fields().get(0).name());
    }

    @Test
    void simpleBindings_exposeIndexNameAndDefault() {
        assertEquals(3, new ClientParamDescriptor.Query(3, "page", "0").paramIndex());
        assertEquals("page", new ClientParamDescriptor.Query(3, "page", "0").name());
        assertEquals("0", new ClientParamDescriptor.Query(3, "page", "0").defaultValue());
        assertEquals("h", new ClientParamDescriptor.Header(0, "h", null).name());
        assertEquals("c", new ClientParamDescriptor.Cookie(0, "c", null).name());
        assertEquals("f", new ClientParamDescriptor.Form(0, "f", null).name());
        assertEquals("m", new ClientParamDescriptor.Matrix(0, "m", null).name());
        assertEquals("p", new ClientParamDescriptor.Path(0, "p", null).name());
    }
}
