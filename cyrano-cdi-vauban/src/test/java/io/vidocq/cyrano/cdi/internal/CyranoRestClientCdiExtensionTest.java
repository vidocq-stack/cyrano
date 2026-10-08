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

import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.ClassType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CyranoRestClientCdiExtension} at build time: on the Vidocq runtime the extension runs inside the Vauban
 * annotation processor, while the application's {@code @RegisterRestClient} interface is being compiled — the
 * language model knows it, but it cannot be loaded as a class.
 *
 * <p>MP Rest Client 4.0 §6.1/§6.2: a CDI bean qualified {@code @RestClient} must exist for each
 * {@code @RegisterRestClient} interface. The language-model and SPI doubles below are hand-written (JDK
 * {@link Proxy}, test only): they answer the calls the extension makes and record the bean it declares.</p>
 */
class CyranoRestClientCdiExtensionTest {

    /** A name no loader of this test can load, as for a class javac is still compiling. */
    private static final String NOT_YET_COMPILED = "app.GreetingClient";

    /** BUG-20261008-06. */
    @Test
    void synthesis_declaresTheBeanFromTheLanguageModel_whenTheInterfaceCannotBeLoaded_spec_section6_1() {
        var extension = new CyranoRestClientCdiExtension();
        ClassInfo iface = classInfo(NOT_YET_COMPILED,
                annotation(RegisterRestClient.class.getName(), Map.of("baseUri", stringMember("http://localhost:1"))));
        extension.discoverRegisterRestClient(double_(ClassConfig.class, Map.of("info", iface)), null);

        var calls = new ArrayList<String>();
        extension.synthesizeRestClientBeans(syntheticComponents(calls), types());

        assertTrue(calls.contains("addBean " + Object.class.getName()), calls.toString());
        assertTrue(calls.contains("type " + NOT_YET_COMPILED), calls.toString());
        assertTrue(calls.contains("qualifier " + RestClient.class.getName()), calls.toString());
        assertTrue(calls.contains("withParam " + CyranoRestClientSyntheticCreator.PARAM_INTERFACE_NAME + "="
                + NOT_YET_COMPILED), calls.toString());
        assertEquals(1, calls.stream().filter(c -> c.startsWith("addBean")).count(), calls.toString());
    }

    // --- hand-written doubles --------------------------------------------------------------------

    /** An instance of {@code type} answering the named no-arg methods from {@code answers}. */
    @SuppressWarnings("unchecked")
    private static <T> T double_(Class<T> type, Map<String, Object> answers) {
        return (T) Proxy.newProxyInstance(CyranoRestClientCdiExtensionTest.class.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, args) -> {
                    if (answers.containsKey(method.getName())) {
                        return answers.get(method.getName());
                    }
                    if (method.getName().equals("toString")) {
                        return type.getSimpleName() + answers;
                    }
                    throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName());
                });
    }

    private static ClassInfo classInfo(String name, AnnotationInfo... annotations) {
        return double_(ClassInfo.class, Map.of("name", name, "isInterface", true, "annotations", List.of(annotations)));
    }

    private static AnnotationInfo annotation(String name, Map<String, AnnotationMember> members) {
        return double_(AnnotationInfo.class, Map.of("name", name, "members", members));
    }

    private static AnnotationMember stringMember(String value) {
        return double_(AnnotationMember.class, Map.of("isString", true, "asString", value));
    }

    private static Types types() {
        return (Types) Proxy.newProxyInstance(CyranoRestClientCdiExtensionTest.class.getClassLoader(),
                new Class<?>[]{Types.class}, (proxy, method, args) -> {
                    if (method.getName().equals("ofClass") && args[0] instanceof ClassInfo info) {
                        return double_(ClassType.class, Map.of("declaration", info));
                    }
                    throw new UnsupportedOperationException("Types." + method.getName());
                });
    }

    private static SyntheticComponents syntheticComponents(List<String> calls) {
        Object[] builder = new Object[1];
        builder[0] = Proxy.newProxyInstance(CyranoRestClientCdiExtensionTest.class.getClassLoader(),
                new Class<?>[]{SyntheticBeanBuilder.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "type" -> calls.add("type " + (args[0] instanceof Class<?> c ? c.getName()
                                : args[0] instanceof ClassType t ? t.declaration().name() : String.valueOf(args[0])));
                        case "qualifier" -> calls.add("qualifier " + (args[0] instanceof Class<?> c ? c.getName()
                                : String.valueOf(args[0])));
                        case "withParam" -> calls.add("withParam " + args[0] + "=" + args[1]);
                        case "toString" -> {
                            return "SyntheticBeanBuilder";
                        }
                        default -> calls.add(method.getName());
                    }
                    return proxy;
                });
        return (SyntheticComponents) Proxy.newProxyInstance(CyranoRestClientCdiExtensionTest.class.getClassLoader(),
                new Class<?>[]{SyntheticComponents.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addBean")) {
                        calls.add("addBean " + ((Class<?>) args[0]).getName());
                        return builder[0];
                    }
                    throw new UnsupportedOperationException("SyntheticComponents." + method.getName());
                });
    }
}
