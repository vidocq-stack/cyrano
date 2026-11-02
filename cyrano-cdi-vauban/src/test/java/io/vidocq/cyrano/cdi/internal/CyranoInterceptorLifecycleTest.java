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

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The interceptors of a client are created once, as for any intercepted bean instance, and destroyed when the client
 * is released: they used to be created on every call and never destroyed, so {@code @PreDestroy} never ran.
 */
class CyranoInterceptorLifecycleTest {

    private static final String RESULT = "result";

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Counted {
    }

    @Counted
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class CountingInterceptor {
        static final AtomicInteger CREATED = new AtomicInteger();
        static final AtomicInteger DESTROYED = new AtomicInteger();

        public CountingInterceptor() {
            CREATED.incrementAndGet();
        }

        @AroundInvoke
        public Object around(InvocationContext ctx) throws Exception {
            return "[" + ctx.proceed() + "]";
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @Counted
    public interface CountedApi {
        String first(String arg);

        String second(String arg);
    }

    private final CyranoCdiProviderInstantiator instantiator = new CyranoCdiProviderInstantiator();

    @BeforeEach
    void reset() {
        CountingInterceptor.CREATED.set(0);
        CountingInterceptor.DESTROYED.set(0);
    }

    private Object call(String name) throws Exception {
        Method method = CountedApi.class.getMethod(name, String.class);
        return instantiator.aroundInvoke(CountedApi.class, this, method, new Object[] {"x"}, () -> RESULT);
    }

    @Test
    void theInterceptorsOfAClientAreCreatedOnceAndDestroyedOnRelease() throws Exception {
        try (var _ = VaubanContainer.builder().addBeanClass(CountingInterceptor.class).build()) {
            for (int i = 0; i < 5; i++) {
                assertEquals("[" + RESULT + "]", call("first"));
                assertEquals("[" + RESULT + "]", call("second"));
            }
            assertEquals(1, CountingInterceptor.CREATED.get(), "one interceptor instance per client");
            assertEquals(0, CountingInterceptor.DESTROYED.get());

            instantiator.release();

            assertEquals(1, CountingInterceptor.DESTROYED.get(), "@PreDestroy runs when the client is released");
            assertEquals(RESULT, call("first"), "a released client creates no interceptor again");
            assertEquals(1, CountingInterceptor.CREATED.get());
        }
    }
}
