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

import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;
import org.eclipse.microprofile.rest.client.ext.QueryParamStyle;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Minimum implementation of {@link Configuration} for {@code RestClientBuilder}.
 * M1: only the bare minimum needed to satisfy the contract
 * {@link jakarta.ws.rs.core.Configurable}. Registered providers are stored
 * but not yet applied to the pipeline; This will follow in M2.
 */
public final class CyranoClientConfiguration implements Configuration {

    private final Map<String, Object> properties = new HashMap<>();
    private final Map<Class<?>, Object> instances = new HashMap<>();
    private final Map<Class<?>, Map<Class<?>, Integer>> contracts = new HashMap<>();
    private final Set<Class<? extends Feature>> enabledFeatures = new java.util.HashSet<>();
    private final Set<Feature> enabledFeatureInstances = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Map<String, Object> builderHeaders = new LinkedHashMap<>();
    private QueryParamStyle queryParamStyle = QueryParamStyle.MULTI_PAIRS;
    private long connectTimeoutMs = -1;
    private long readTimeoutMs = -1;
    private boolean followRedirects;
    private String proxyHost;
    private int proxyPort = -1;
    private ExecutorService executorService;

    void putProperty(String name, Object value) {
        properties.put(name, value);
    }

    void addBuilderHeader(String name, Object value) {
        builderHeaders.put(name, value);
    }

    Map<String, Object> getBuilderHeaders() {
        return builderHeaders;
    }

    void setQueryParamStyle(QueryParamStyle style) {
        this.queryParamStyle = style != null ? style : QueryParamStyle.MULTI_PAIRS;
    }

    QueryParamStyle getQueryParamStyle() {
        return queryParamStyle;
    }

    void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    void setFollowRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
    }

    boolean isFollowRedirects() {
        return followRedirects;
    }

    void setProxyAddress(String host, int port) {
        this.proxyHost = host;
        this.proxyPort = port;
    }

    String getProxyHost() {
        return proxyHost;
    }

    int getProxyPort() {
        return proxyPort;
    }

    void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    void registerProvider(Class<?> componentClass, Map<Class<?>, Integer> contractsMap) {
        // M4-3 — first goes through the ProviderInstantiator (CDI if present), with reflective fallback
        Object instance = io.vidocq.cyrano.runtime.ProviderInstantiator.current().create(componentClass);
        if (instance == null) {
            instance = io.vidocq.cyrano.runtime.ProviderInstantiator.defaultInstantiator().create(componentClass);
        }
        instances.put(componentClass, instance);
        contracts.put(componentClass, contractsMap);
    }

    void registerProvider(Object component, Map<Class<?>, Integer> contractsMap) {
        instances.put(component.getClass(), component);
        contracts.put(component.getClass(), contractsMap);
    }

    void markFeatureEnabled(Feature feature) {
        if (feature == null) return;
        enabledFeatureInstances.add(feature);
        @SuppressWarnings("unchecked")
        Class<? extends Feature> featureClass = (Class<? extends Feature>) feature.getClass();
        enabledFeatures.add(featureClass);
    }

    @Override public RuntimeType getRuntimeType() { return RuntimeType.CLIENT; }
    @Override public Map<String, Object> getProperties() { return Collections.unmodifiableMap(properties); }
    @Override public Object getProperty(String name) { return properties.get(name); }
    @Override public Collection<String> getPropertyNames() { return Collections.unmodifiableSet(properties.keySet()); }
    @Override public boolean isEnabled(Feature feature) { return enabledFeatureInstances.contains(feature); }
    @Override public boolean isEnabled(Class<? extends Feature> featureClass) { return enabledFeatures.contains(featureClass); }
    @Override public boolean isRegistered(Object component) { return instances.containsValue(component); }
    @Override public boolean isRegistered(Class<?> componentClass) { return instances.containsKey(componentClass); }
    @Override public Map<Class<?>, Integer> getContracts(Class<?> componentClass) {
        Map<Class<?>, Integer> c = contracts.get(componentClass);
        return c == null ? Map.of() : Collections.unmodifiableMap(c);
    }
    @Override public Set<Class<?>> getClasses() { return Collections.unmodifiableSet(instances.keySet()); }
    @Override public Set<Object> getInstances() { return Set.copyOf(instances.values()); }

    // ------------------------------------------------------------
    //SPI filters (MP Rest Client §4.2 + JAX-RS §6.3) — M4-2
    // ------------------------------------------------------------

    /**
     * Returns registered {@link ClientRequestFilter}, sorted by ascending priority
     * (the lowest priority — thus {@code Priorities.AUTHENTICATION = 1000} — runs
     * first). The priority comes first from the contracts map passed to
     * {@code register(...)}, otherwise from the {@code @Priority} annotation,
     * otherwise {@code Priorities.USER} = 5000.
     */
    List<ClientRequestFilter> getRequestFilters() {
        List<FilterEntry<ClientRequestFilter>> entries = new ArrayList<>();
        for (Object inst : instances.values()) {
            if (inst instanceof ClientRequestFilter f) {
                entries.add(new FilterEntry<>(f, resolvePriority(inst, ClientRequestFilter.class)));
            }
        }
        entries.sort((a, b) -> Integer.compare(a.priority(), b.priority()));
        List<ClientRequestFilter> out = new ArrayList<>(entries.size());
        for (var e : entries) out.add(e.filter());
        return out;
    }

    /**
     * Returns registered {@link ClientResponseFilter}, sorted by descending priority
     * (highest runs first — reverse direction of request filters, according to
     * to JAX-RS §6.3.
     */
    List<ClientResponseFilter> getResponseFilters() {
        List<FilterEntry<ClientResponseFilter>> entries = new ArrayList<>();
        for (Object inst : instances.values()) {
            if (inst instanceof ClientResponseFilter f) {
                entries.add(new FilterEntry<>(f, resolvePriority(inst, ClientResponseFilter.class)));
            }
        }
        entries.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        List<ClientResponseFilter> out = new ArrayList<>(entries.size());
        for (var e : entries) out.add(e.filter());
        return out;
    }

    private int resolvePriority(Object component, Class<?> contract) {
        Map<Class<?>, Integer> c = contracts.get(component.getClass());
        if (c != null) {
            Integer p = c.get(contract);
            if (p != null) return p;
            //global priority (without targeted contract) — register(obj, priority)
            if (c.size() == 1) {
                Integer only = c.values().iterator().next();
                if (only != null) return only;
            }
        }
        //jakarta.annotation.Priority — Reflective detection to avoid adding compile-time dependency
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> prioCls =
                    (Class<? extends Annotation>) Class.forName("jakarta.annotation.Priority");
            Annotation a = component.getClass().getAnnotation(prioCls);
            if (a != null) {
                Integer v = (Integer) prioCls.getMethod("value").invoke(a);
                if (v != null) return v;
            }
        } catch (ClassNotFoundException ignored) {
            // jakarta.annotation is absent — this is fine
        } catch (ReflectiveOperationException ignored) {
            // ignored — falls back to Priorities.USER
        }
        return jakarta.ws.rs.Priorities.USER; // 5000
    }

    List<WriterInterceptor> getWriterInterceptors() {
        List<WriterInterceptor> result = new ArrayList<>();
        for (Object inst : instances.values()) {
            if (inst instanceof WriterInterceptor wi) result.add(wi);
        }
        return result;
    }

    List<ReaderInterceptor> getReaderInterceptors() {
        List<ReaderInterceptor> result = new ArrayList<>();
        for (Object inst : instances.values()) {
            if (inst instanceof ReaderInterceptor ri) result.add(ri);
        }
        return result;
    }

    private record FilterEntry<F>(F filter, int priority) {}
}

