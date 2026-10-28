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

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link CyranoCdiProviderInstantiator}: MP Rest Client 4.0 §4.2.4 — when CDI is present, providers that are
 * managed beans come from the {@code BeanManager}, and the CDI interceptors bound to a client method surround
 * its invocation.
 */
class CyranoCdiProviderInstantiatorTest {

    private static final String RESULT = "result";

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Wrapped {
    }

    /** Wraps the result in brackets and records what the invocation context exposes. */
    @Wrapped
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class WrappingInterceptor {
        static final List<Object> SEEN = new ArrayList<>();

        @AroundInvoke
        public Object wrap(InvocationContext ctx) throws Exception {
            SEEN.clear();
            SEEN.add(ctx.getTarget());
            SEEN.add(ctx.getMethod());
            ctx.setParameters(new Object[] {"replaced"});
            SEEN.add(ctx.getParameters()[0]);
            ctx.getContextData().put("k", "v");
            SEEN.add(ctx.getContextData().get("k"));
            SEEN.add(ctx.getConstructor());
            SEEN.add(ctx.getTimer());
            return "[" + ctx.proceed() + "]";
        }
    }

    /** A provider class the application declares as a managed bean. */
    @Dependent
    public static class ManagedProvider {
    }

    /** A provider class that is not a bean. */
    public static class PlainProvider {
    }

    /** A client interface whose method carries the interceptor binding. */
    public interface InterceptedApi {
        @Wrapped
        String call(String arg);

        String plain(String arg);
    }

    /** A client interface bound as a whole. */
    @Wrapped
    public interface WrappedApi {
        String call(String arg);
    }

    private final CyranoCdiProviderInstantiator instantiator = new CyranoCdiProviderInstantiator();

    private static Method method(Class<?> type, String name) throws NoSuchMethodException {
        return type.getMethod(name, String.class);
    }

    private static VaubanContainer container() {
        return VaubanContainer.builder()
                .addBeanClass(ManagedProvider.class)
                .addBeanClass(WrappingInterceptor.class)
                .build();
    }

    @Test
    void outside_cdi_providers_are_left_to_reflection_and_invocations_run_unchanged() throws Exception {
        assertNull(instantiator.create(ManagedProvider.class));
        assertEquals(RESULT, instantiator.aroundInvoke(this, method(InterceptedApi.class, "call"),
                new Object[] {"x"}, () -> RESULT));
    }

    @Test
    void managed_provider_comes_from_the_bean_manager_spec_section4_2_4() {
        try (var _ = container()) {
            assertInstanceOf(ManagedProvider.class, instantiator.create(ManagedProvider.class));
            assertNull(instantiator.create(PlainProvider.class));
        }
    }

    @Test
    void bound_interceptor_surrounds_the_invocation() throws Exception {
        try (var _ = container()) {
            Method call = method(InterceptedApi.class, "call");

            Object result = instantiator.aroundInvoke(this, call, new Object[] {"x"}, () -> RESULT);

            assertEquals("[" + RESULT + "]", result);
            assertSame(this, WrappingInterceptor.SEEN.get(0));
            assertEquals(call, WrappingInterceptor.SEEN.get(1));
            assertEquals(List.of("replaced", "v"), WrappingInterceptor.SEEN.subList(2, 4));
            assertNull(WrappingInterceptor.SEEN.get(4));
            assertNull(WrappingInterceptor.SEEN.get(5));
            assertEquals("[" + RESULT + "]", instantiator.aroundInvoke(this, method(WrappedApi.class, "call"),
                    new Object[] {"x"}, () -> RESULT));
        }
    }

    @Test
    void method_without_binding_is_invoked_directly() throws Exception {
        try (var _ = container()) {
            assertEquals(RESULT, instantiator.aroundInvoke(this, method(InterceptedApi.class, "plain"),
                    null, () -> RESULT));
        }
    }
}
