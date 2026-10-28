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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ConversationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.ClassType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    /** A client interface this test can load. */
    private static final String LOADABLE = CyranoRestClientCdiIntegrationTest.PingApi.class.getName();
    private static final String SCOPE_SUFFIX = "/mp-rest/scope";
    private static final String CONFIG_KEY = "ext-test";

    @AfterEach
    void clearConfig() {
        System.clearProperty(LOADABLE + SCOPE_SUFFIX);
        System.clearProperty(CONFIG_KEY + SCOPE_SUFFIX);
    }

    /** Discovers {@code info}, synthesizes, and returns the calls made on the synthetic bean builder. */
    private static List<String> synthesize(ClassInfo info) {
        var extension = new CyranoRestClientCdiExtension();
        extension.discoverRegisterRestClient(double_(ClassConfig.class, Map.of("info", info)), null);
        var calls = new ArrayList<String>();
        extension.synthesizeRestClientBeans(syntheticComponents(calls), types());
        return calls;
    }

    private static ClassInfo registeredClient(Map<String, AnnotationMember> members, AnnotationInfo... others) {
        List<AnnotationInfo> annotations = new ArrayList<>(List.of(others));
        annotations.add(annotation(RegisterRestClient.class.getName(), members));
        return double_(ClassInfo.class, Map.of("name", LOADABLE, "isInterface", true, "annotations", annotations));
    }

    @Test
    void loadable_interface_gets_a_dependent_bean_of_its_own_type_spec_section6_3() {
        List<String> calls = synthesize(registeredClient(Map.of("configKey", stringMember(CONFIG_KEY))));

        assertTrue(calls.contains("addBean " + LOADABLE), calls.toString());
        assertTrue(calls.contains("type " + LOADABLE), calls.toString());
        assertTrue(calls.contains("scope " + Dependent.class.getName()), calls.toString());
        assertTrue(calls.contains("withParam " + CyranoRestClientSyntheticCreator.PARAM_CONFIG_KEY + "=" + CONFIG_KEY),
                calls.toString());
        assertTrue(calls.contains("withParam " + CyranoRestClientSyntheticCreator.PARAM_BASE_URI + "="),
                calls.toString());
    }

    @ParameterizedTest
    @ValueSource(classes = {ApplicationScoped.class, RequestScoped.class, SessionScoped.class, Singleton.class,
            ConversationScoped.class, Dependent.class})
    void scope_annotation_of_the_interface_is_the_bean_scope_spec_section6_3(Class<?> scope) {
        List<String> calls = synthesize(registeredClient(Map.of(), annotation(scope.getName(), Map.of())));

        assertTrue(calls.contains("scope " + scope.getName()), calls.toString());
    }

    @Test
    void configured_scope_overrides_the_annotation_spec_section6_3() {
        System.setProperty(LOADABLE + SCOPE_SUFFIX, Singleton.class.getName());

        List<String> calls = synthesize(registeredClient(Map.of(),
                annotation(ApplicationScoped.class.getName(), Map.of())));

        assertTrue(calls.contains("scope " + Singleton.class.getName()), calls.toString());
    }

    @Test
    void scope_configured_under_the_config_key_applies_and_unknown_scopes_are_dependent() {
        System.setProperty(CONFIG_KEY + SCOPE_SUFFIX, RequestScoped.class.getName());
        List<String> fromKey = synthesize(registeredClient(Map.of("configKey", stringMember(CONFIG_KEY))));
        assertTrue(fromKey.contains("scope " + RequestScoped.class.getName()), fromKey.toString());

        System.setProperty(LOADABLE + SCOPE_SUFFIX, "com.example.UnknownScoped");
        List<String> unknown = synthesize(registeredClient(Map.of("configKey", stringMember(CONFIG_KEY))));
        assertTrue(unknown.contains("scope " + Dependent.class.getName()), unknown.toString());
    }

    @Test
    void non_string_members_count_as_absent() {
        AnnotationMember notAString = double_(AnnotationMember.class, Map.of("isString", false));

        List<String> calls = synthesize(registeredClient(Map.of("baseUri", notAString)));

        assertTrue(calls.contains("withParam " + CyranoRestClientSyntheticCreator.PARAM_BASE_URI + "="),
                calls.toString());
    }

    @Test
    void interface_is_loaded_through_the_module_loader_without_a_context_loader() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(null);
        try {
            List<String> calls = synthesize(registeredClient(Map.of()));
            assertTrue(calls.contains("addBean " + LOADABLE), calls.toString());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void annotated_class_is_rejected_with_an_error_spec_section3_1() {
        var errors = new ArrayList<String>();
        Messages messages = (Messages) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Messages.class}, (proxy, method, args) -> {
                    errors.add(method.getName() + " " + args[0]);
                    return null;
                });
        ClassInfo aClass = double_(ClassInfo.class, Map.of("name", "app.NotAnInterface", "isInterface", false));
        var extension = new CyranoRestClientCdiExtension();

        extension.discoverRegisterRestClient(double_(ClassConfig.class, Map.of("info", aClass)), messages);
        extension.discoverRegisterRestClient(double_(ClassConfig.class, Map.of("info", aClass)), null);

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().startsWith("error ") && errors.getFirst().contains("app.NotAnInterface"),
                errors.toString());
        var calls = new ArrayList<String>();
        extension.synthesizeRestClientBeans(syntheticComponents(calls), types());
        assertTrue(calls.isEmpty(), calls.toString());
    }

    @Test
    void interface_without_the_annotation_is_ignored() {
        ClassInfo plain = double_(ClassInfo.class, Map.of("name", LOADABLE, "isInterface", true,
                "annotations", List.of()));
        var extension = new CyranoRestClientCdiExtension();
        extension.discoverRegisterRestClient(double_(ClassConfig.class, Map.of("info", plain)), null);

        var calls = new ArrayList<String>();
        extension.synthesizeRestClientBeans(syntheticComponents(calls), types());

        assertTrue(calls.isEmpty(), calls.toString());
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
                        case "scope" -> calls.add("scope " + ((Class<?>) args[0]).getName());
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
