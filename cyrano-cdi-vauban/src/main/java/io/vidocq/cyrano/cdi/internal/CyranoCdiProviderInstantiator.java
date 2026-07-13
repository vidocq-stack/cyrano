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

import io.vidocq.cyrano.runtime.ProviderInstantiator;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.interceptor.InvocationContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * CDI-aware implementation of {@link ProviderInstantiator} — resolves providers
 * (filters, interceptors, mappers...) via the current {@link BeanManager} if a managed
 * bean exists for the requested class. Otherwise returns {@code null} to let
 * cyrano-core fall back on reflective instantiation.
 *
 * <p>Spec MP Rest Client 4.0 §4.2.4 — <em>« RestClient implementations must use the
 * BeanManager to obtain providers if they are managed beans. »</em></p>
 *
 * <p>Discovered via {@code META-INF/services/io.vidocq.cyrano.runtime.ProviderInstantiator}
 * and Java Modules {@code provides}.</p>
 *
 * @since 0.1.0 (M4-3)
 */
public final class CyranoCdiProviderInstantiator implements ProviderInstantiator {

    @Override
    public Object create(Class<?> componentClass) {
        BeanManager bm;
        try {
            bm = CDI.current().getBeanManager();
        } catch (IllegalStateException unavailable) {
            //CDI not yet started (or outside CDI) — delegates to reflective fallback
            return null;
        }
        var beans = bm.getBeans(componentClass, new Annotation[0]);
        if (beans == null || beans.isEmpty()) return null;
        var bean = bm.resolve(beans);
        if (bean == null) return null;
        var ctx = bm.createCreationalContext(bean);
        return bm.getReference(bean, componentClass, ctx);
    }

    @Override
    public Object aroundInvoke(Object target, Method method, Object[] args, Callable<Object> invocation) throws Exception {
        BeanManager bm;
        try {
            bm = CDI.current().getBeanManager();
        } catch (IllegalStateException unavailable) {
            return invocation.call();
        }

        Annotation[] bindings = interceptorBindings(method);
        if (bindings.length == 0) {
            return invocation.call();
        }

        List<Interceptor<?>> interceptors = bm.resolveInterceptors(InterceptionType.AROUND_INVOKE, bindings);
        if (interceptors == null || interceptors.isEmpty()) {
            return invocation.call();
        }

        List<InterceptorInvocation> chain = new ArrayList<>(interceptors.size());
        for (Interceptor<?> interceptor : interceptors) {
            var ctx = bm.createCreationalContext(interceptor);
            Object instance = bm.getReference(interceptor, interceptor.getBeanClass(), ctx);
            chain.add(new InterceptorInvocation(interceptor, instance));
        }

        return new InvocationContextImpl(target, method, args, invocation, chain).proceed();
    }

    private static Annotation[] interceptorBindings(Method method) {
        List<Annotation> out = new ArrayList<>();
        collectBindings(out, method.getDeclaringClass().getAnnotations());
        collectBindings(out, method.getAnnotations());
        return out.toArray(new Annotation[0]);
    }

    private static void collectBindings(List<Annotation> out, Annotation[] candidates) {
        for (Annotation candidate : candidates) {
            if (candidate.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                out.add(candidate);
            }
        }
    }

    private record InterceptorInvocation(Interceptor<?> interceptor, Object instance) {}

    private static final class InvocationContextImpl implements InvocationContext {
        private final Object target;
        private final Method method;
        private final Object[] args;
        private final Callable<Object> terminal;
        private final List<InterceptorInvocation> chain;
        private final Map<String, Object> contextData = new HashMap<>();
        private int index;

        private InvocationContextImpl(Object target,
                                      Method method,
                                      Object[] args,
                                      Callable<Object> terminal,
                                      List<InterceptorInvocation> chain) {
            this.target = target;
            this.method = method;
            this.args = args == null ? new Object[0] : args.clone();
            this.terminal = terminal;
            this.chain = chain;
        }

        @Override
        public Object getTarget() {
            return target;
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Constructor<?> getConstructor() {
            return null;
        }

        @Override
        public Object[] getParameters() {
            return args.clone();
        }

        @Override
        public void setParameters(Object[] params) {
            if (params == null || params.length != args.length) {
                throw new IllegalArgumentException("Invalid interception parameters");
            }
            System.arraycopy(params, 0, args, 0, args.length);
        }

        @Override
        public Map<String, Object> getContextData() {
            return contextData;
        }

        @Override
        public Object proceed() throws Exception {
            if (index >= chain.size()) {
                return terminal.call();
            }
            InterceptorInvocation current = chain.get(index++);
            try {
                return intercept(current, this);
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw ex;
            } catch (Throwable th) {
                throw new Exception(th);
            }
        }

        @Override
        public Object getTimer() {
            return null;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static Object intercept(InterceptorInvocation current, InvocationContext ctx) throws Exception {
            return ((Interceptor) current.interceptor()).intercept(InterceptionType.AROUND_INVOKE, current.instance(), ctx);
        }
    }
}
