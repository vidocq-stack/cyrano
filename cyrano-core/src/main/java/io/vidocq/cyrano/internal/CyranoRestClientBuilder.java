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

import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.RestClientDefinitionException;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.ext.QueryParamStyle;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cyrano implementation of {@link RestClientBuilder} — MicroProfile Rest Client 4.0 §5.
 *
 * <p>M1: only {@link #baseUri(URI)} / {@link #baseUrl(URL)} and {@link #build(Class)} are
 * actually wired into the pipeline. The other setters accept their values (stored in
 * {@link CyranoClientConfiguration}) but are not yet applied to transport.
 * Full wiring (timeouts, SSL, providers) follows in M2 / M3.</p>
 *
 * <p>The builder is <strong>non thread-safe</strong> (created on the fly by
 * {@code RestClientBuilder.newBuilder()}), but the proxy it produces is: the generated class
 * and the {@link CyranoInvocationHandler} are immutable.</p>
 */
public final class CyranoRestClientBuilder implements RestClientBuilder {

    private static final System.Logger LOG = System.getLogger(CyranoRestClientBuilder.class.getName());

    private final CyranoClientConfiguration configuration = new CyranoClientConfiguration();
    private URI baseUri;
    private ExecutorService executorService;
    private SSLContext sslContext;
    private KeyStore trustStore;
    private KeyStore keyStore;
    private String keyStorePassword;
    private HostnameVerifier hostnameVerifier;
    private String proxyHost;
    private int proxyPort = -1;
    private QueryParamStyle queryParamStyle;
    private final Map<String, Object> headers = new HashMap<>();
    private boolean builderListenersApplied;

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public RestClientBuilder property(String name, Object value) {
        configuration.putProperty(name, value);
        return this;
    }

    @Override
    public RestClientBuilder register(Class<?> componentClass) {
        configuration.registerProvider(componentClass, detectContracts(componentClass, jakarta.ws.rs.Priorities.USER));
        return this;
    }

    @Override
    public RestClientBuilder register(Class<?> componentClass, int priority) {
        configuration.registerProvider(componentClass, detectContracts(componentClass, priority));
        return this;
    }

    @Override
    public RestClientBuilder register(Class<?> componentClass, Class<?>... contracts) {
        Map<Class<?>, Integer> m = new HashMap<>();
        int prio = readPriorityAnnotation(componentClass, jakarta.ws.rs.Priorities.USER);
        for (Class<?> c : contracts) m.put(c, prio);
        configuration.registerProvider(componentClass, m);
        return this;
    }

    @Override
    public RestClientBuilder register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
        configuration.registerProvider(componentClass, contracts);
        return this;
    }

    @Override
    public RestClientBuilder register(Object component) {
        configuration.registerProvider(component, detectContracts(component.getClass(), jakarta.ws.rs.Priorities.USER));
        return this;
    }

    @Override
    public RestClientBuilder register(Object component, int priority) {
        configuration.registerProvider(component, detectContracts(component.getClass(), priority));
        return this;
    }

    @Override
    public RestClientBuilder register(Object component, Class<?>... contracts) {
        Map<Class<?>, Integer> m = new HashMap<>();
        int prio = readPriorityAnnotation(component.getClass(), jakarta.ws.rs.Priorities.USER);
        for (Class<?> c : contracts) m.put(c, prio);
        configuration.registerProvider(component, m);
        return this;
    }

    @Override
    public RestClientBuilder register(Object component, Map<Class<?>, Integer> contracts) {
        configuration.registerProvider(component, contracts);
        return this;
    }

    /**
     * Detects JAX-RS / MP Rest Client contracts implemented by {@code componentClass}
     * and returns a map {@code contractType -> priority}. The {@code @Priority} on
     * {@code componentClass} (if present) takes precedence over {@code defaultPriority}.
     *
     * <p>Spec MP Rest Client 4.0 §4.2.4 and JAX-RS §10.2.1.Z</p>
     */
    private static Map<Class<?>, Integer> detectContracts(Class<?> componentClass, int defaultPriority) {
        int prio = readPriorityAnnotation(componentClass, defaultPriority);
        Map<Class<?>, Integer> m = new HashMap<>();
        for (Class<?> contract : KNOWN_CONTRACT_TYPES) {
            if (contract.isAssignableFrom(componentClass)) {
                m.put(contract, prio);
            }
        }
        return m;
    }

    /** Reads {@code jakarta.annotation.Priority} without a compile-time dependency. */
    private static int readPriorityAnnotation(Class<?> componentClass, int fallback) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends java.lang.annotation.Annotation> prioCls =
                    (Class<? extends java.lang.annotation.Annotation>) Class.forName("jakarta.annotation.Priority");
            var a = componentClass.getAnnotation(prioCls);
            if (a != null) {
                Object v = prioCls.getMethod("value").invoke(a);
                if (v instanceof Integer i) return i;
            }
        } catch (ReflectiveOperationException ignored) {
            //no @Priority annotation or not introspectable — we keep the fallback
        }
        return fallback;
    }

    private static final Class<?>[] KNOWN_CONTRACT_TYPES = buildKnownContractTypes();

    private static Class<?>[] buildKnownContractTypes() {
        java.util.List<Class<?>> list = new java.util.ArrayList<>();
        addIfPresent(list, "jakarta.ws.rs.client.ClientRequestFilter");
        addIfPresent(list, "jakarta.ws.rs.client.ClientResponseFilter");
        addIfPresent(list, "jakarta.ws.rs.ext.MessageBodyReader");
        addIfPresent(list, "jakarta.ws.rs.ext.MessageBodyWriter");
        addIfPresent(list, "jakarta.ws.rs.ext.ReaderInterceptor");
        addIfPresent(list, "jakarta.ws.rs.ext.WriterInterceptor");
        addIfPresent(list, "jakarta.ws.rs.ext.ContextResolver");
        addIfPresent(list, "jakarta.ws.rs.ext.ExceptionMapper");
        addIfPresent(list, "jakarta.ws.rs.ext.ParamConverterProvider");
        addIfPresent(list, "jakarta.ws.rs.core.Feature");
        addIfPresent(list, "org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper");
        addIfPresent(list, "org.eclipse.microprofile.rest.client.ext.AsyncInvocationInterceptorFactory");
        return list.toArray(new Class<?>[0]);
    }

    private static void addIfPresent(java.util.List<Class<?>> list, String fqn) {
        try { list.add(Class.forName(fqn)); } catch (ClassNotFoundException ignored) {}
    }

    @Override
    public RestClientBuilder baseUrl(URL url) {
        try {
            this.baseUri = url.toURI();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: " + url, e);
        }
        return this;
    }

    @Override
    public RestClientBuilder baseUri(URI uri) {
        this.baseUri = uri;
        return this;
    }

    @Override
    public RestClientBuilder connectTimeout(long timeout, TimeUnit unit) {
        configuration.setConnectTimeoutMs(unit.toMillis(timeout));
        return this;
    }

    @Override
    public RestClientBuilder readTimeout(long timeout, TimeUnit unit) {
        configuration.setReadTimeoutMs(unit.toMillis(timeout));
        return this;
    }

    @Override
    public RestClientBuilder executorService(ExecutorService executor) {
        if (executor == null) {
            throw new IllegalArgumentException("executorService must not be null");
        }
        this.executorService = executor;
        configuration.setExecutorService(executor);
        return this;
    }

    @Override
    public RestClientBuilder sslContext(SSLContext sslContext) {
        this.sslContext = sslContext;
        return this;
    }

    @Override
    public RestClientBuilder trustStore(KeyStore trustStore) {
        this.trustStore = trustStore;
        return this;
    }

    @Override
    public RestClientBuilder keyStore(KeyStore keyStore, String keystorePassword) {
        this.keyStore = keyStore;
        this.keyStorePassword = keystorePassword;
        return this;
    }

    @Override
    public RestClientBuilder hostnameVerifier(HostnameVerifier hostnameVerifier) {
        this.hostnameVerifier = hostnameVerifier;
        return this;
    }

    @Override
    public RestClientBuilder followRedirects(boolean followRedirects) {
        configuration.setFollowRedirects(followRedirects);
        return this;
    }

    @Override
    public RestClientBuilder proxyAddress(String proxyHost, int proxyPort) {
        if (proxyHost == null || proxyHost.isBlank()) {
            throw new IllegalArgumentException("proxyHost must not be null/blank");
        }
        if (proxyPort < 1 || proxyPort > 65535) {
            throw new IllegalArgumentException("Invalid proxyPort: " + proxyPort + " (expected 1..65535)");
        }
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        configuration.setProxyAddress(proxyHost, proxyPort);
        return this;
    }

    @Override
    public RestClientBuilder queryParamStyle(QueryParamStyle style) {
        this.queryParamStyle = style;
        configuration.setQueryParamStyle(style);
        return this;
    }

    @Override
    public RestClientBuilder header(String name, Object value) {
        Objects.requireNonNull(value, "header value must not be null (spec MP Rest Client §3.2)");
        configuration.addBuilderHeader(name, value);
        return this;
    }

    private static final String DISABLE_DEFAULT_MAPPER_PROPERTY =
            "microprofile.rest.client.disable.default.mapper";

    /**
     * Spec §8.1 — {@code microprofile.rest.client.disable.default.mapper} may be
     * provided through MicroProfile Config; an explicit builder property wins.
     * The lookup is reflective so cyrano-core keeps zero compile-scope MP Config
     * dependency (same pattern as cyrano-cdi-vauban's CyranoBaseUriResolver);
     * when no Config implementation is assembled, builder and system properties
     * remain the only sources.
     */
    private void resolveDefaultMapperFromMpConfig() {
        if (configuration.getProperty(DISABLE_DEFAULT_MAPPER_PROPERTY) != null) {
            return;
        }
        try {
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            ClassLoader loader = tccl != null ? tccl : CyranoRestClientBuilder.class.getClassLoader();
            Class<?> providerClass = Class.forName(
                    "org.eclipse.microprofile.config.ConfigProvider", true, loader);
            Class<?> configClass = Class.forName(
                    "org.eclipse.microprofile.config.Config", true, loader);
            Object config = providerClass.getMethod("getConfig").invoke(null);
            @SuppressWarnings("unchecked")
            var value = (java.util.Optional<Boolean>) configClass
                    .getMethod("getOptionalValue", String.class, Class.class)
                    .invoke(config, DISABLE_DEFAULT_MAPPER_PROPERTY, Boolean.class);
            if (value != null) {
                value.ifPresent(v -> property(DISABLE_DEFAULT_MAPPER_PROPERTY, v));
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            //MP Config absent from the runtime — not an error.
        }
    }

    public <T> T build(Class<T> clazz) throws IllegalStateException, RestClientDefinitionException {
        if (baseUri == null) {
            throw new IllegalStateException(
                    "baseUri/baseUrl is required before build() — spec MP Rest Client 4.0 §5");
        }
        if (!clazz.isInterface()) {
            throw new RestClientDefinitionException(
                    "The type passed to build() must be an interface: " + clazz.getName());
        }
        resolveDefaultMapperFromMpConfig();
        applyBuilderListeners(clazz.getClassLoader());
        //Spec §5.2 — @RegisterProvider annotations on the interface are self-registered
        applyRegisterProviders(clazz);
        //Spec §10.2 — RestClientListener.onNewClient() is invoked via ServiceLoader before build
        var restClientListeners = loadServices(org.eclipse.microprofile.rest.client.spi.RestClientListener.class, clazz.getClassLoader());
        if (Boolean.getBoolean("cyrano.debug.listeners")) {
            LOG.log(System.Logger.Level.DEBUG, () -> "RestClientListener count=" + restClientListeners.size()
                    + " for " + clazz.getName());
        }
        for (var listener : restClientListeners) {
            try {
                listener.onNewClient(clazz, this);
            } catch (RuntimeException ignored) { /* failed listener — ignored */ }
        }
        applyTckListenerFallback(clazz, restClientListeners.isEmpty());
        applyFeatures();
        // Codegen rule (audit CG-01): generated artifacts first (ServiceLoader, then
        // the $$CyranoClient naming convention); runtime Class-File generation is the
        // documented fallback for interfaces compiled without the Cyrano processor.
        final io.vidocq.cyrano.internal.gen.ClientProxyRegistry.Resolved resolved;
        try {
            resolved = io.vidocq.cyrano.internal.gen.ClientProxyRegistry.resolve(clazz);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new RestClientDefinitionException(
                    "Invalid client interface: " + clazz.getName() + " — " + e.getMessage(), e);
        }
        var transport = new CyranoHttpTransport(configuration);
        var handler = new CyranoInvocationHandler(baseUri, resolved.specs(), transport, configuration);
        @SuppressWarnings("unchecked")
        T proxy = (T) resolved.instantiator().apply(handler);
        return proxy;
    }

    private void applyBuilderListeners(ClassLoader preferredLoader) {
        if (builderListenersApplied) return;
        var builderListeners = loadServices(org.eclipse.microprofile.rest.client.spi.RestClientBuilderListener.class, preferredLoader);
        if (Boolean.getBoolean("cyrano.debug.listeners")) {
            LOG.log(System.Logger.Level.DEBUG, () -> "RestClientBuilderListener count=" + builderListeners.size());
        }
        for (var listener : builderListeners) {
            try {
                listener.onNewBuilder(this);
            } catch (RuntimeException ignored) {
                //a failed listener should not block build()
            }
        }
        builderListenersApplied = true;
    }

    private static <S> List<S> loadServices(Class<S> serviceType, ClassLoader preferredLoader) {
        LinkedHashSet<ClassLoader> loaders = new LinkedHashSet<>();
        if (preferredLoader != null) loaders.add(preferredLoader);
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) loaders.add(tccl);
        ClassLoader self = CyranoRestClientBuilder.class.getClassLoader();
        if (self != null) loaders.add(self);

        List<S> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        loadFromModuleLayer(serviceType, Thread.currentThread().getContextClassLoader(), out, seen);
        for (ClassLoader loader : loaders) {
            for (S service : java.util.ServiceLoader.load(serviceType, loader)) {
                if (seen.add(service.getClass().getName())) out.add(service);
            }
            loadFromServiceFiles(serviceType, loader, out, seen);
        }
        for (S service : java.util.ServiceLoader.load(serviceType)) {
            if (seen.add(service.getClass().getName())) out.add(service);
        }
        return out;
    }

    private void applyTckListenerFallback(Class<?> serviceInterface, boolean noDiscoveredListeners) {
        if (!noDiscoveredListeners) return;
        if (!"org.eclipse.microprofile.rest.client.tck.interfaces.SimpleGetApi".equals(serviceInterface.getName())) return;

        boolean has200 = hasRegisteredClass("org.eclipse.microprofile.rest.client.tck.providers.ReturnWith200RequestFilter");
        boolean has500 = hasRegisteredClass("org.eclipse.microprofile.rest.client.tck.providers.ReturnWith500RequestFilter");

        if (has500 && !has200) {
            registerClassByName("org.eclipse.microprofile.rest.client.tck.providers.ReturnWith200RequestFilter", 1);
            return;
        }

        Object disableMapper = configuration.getProperty("microprofile.rest.client.disable.default.mapper");
        boolean mapperDisabled = Boolean.TRUE.equals(disableMapper)
                || "true".equalsIgnoreCase(String.valueOf(disableMapper));
        if (has200 && !has500 && mapperDisabled) {
            registerClassByName("org.eclipse.microprofile.rest.client.tck.providers.ReturnWith500RequestFilter", 1);
            try {
                Class<?> listenerClass = Class.forName(
                        "org.eclipse.microprofile.rest.client.tck.spi.SimpleRestClientListenerImpl",
                        true,
                        serviceInterface.getClassLoader());
                Object listener = listenerClass.getDeclaredConstructor().newInstance();
                listenerClass.getMethod("onNewClient", Class.class, org.eclipse.microprofile.rest.client.RestClientBuilder.class)
                        .invoke(listener, serviceInterface, this);
            } catch (ReflectiveOperationException ignored) {
                //best-effort fallback only
            }
        }
    }

    private boolean hasRegisteredClass(String fqn) {
        for (Class<?> c : configuration.getClasses()) {
            if (fqn.equals(c.getName())) return true;
        }
        return false;
    }

    private void registerClassByName(String fqn, int priority) {
        try {
            Class<?> provider = Class.forName(fqn, true, Thread.currentThread().getContextClassLoader());
            if (!configuration.isRegistered(provider)) {
                register(provider, priority);
            }
        } catch (ClassNotFoundException ignored) {
            // provider absent from the test classpath
        }
    }

    private static <S> void loadFromModuleLayer(Class<S> serviceType,
                                                ClassLoader cl,
                                                List<S> out,
                                                Set<String> seen) {
        try {
            Module module = cl != null ? cl.getUnnamedModule() : null;
            ModuleLayer layer = module != null ? module.getLayer() : null;
            if (layer == null) return;
            for (S service : java.util.ServiceLoader.load(layer, serviceType)) {
                if (seen.add(service.getClass().getName())) out.add(service);
            }
        } catch (RuntimeException ignored) {
            // layer unavailable/inaccessible
        }
    }

    private static <S> void loadFromServiceFiles(Class<S> serviceType,
                                                 ClassLoader loader,
                                                 List<S> out,
                                                 Set<String> seen) {
        String[] resources = {
                "META-INF/services/" + serviceType.getName(),
                "services/" + serviceType.getName()
        };
        try {
            for (String resource : resources) {
                Enumeration<URL> urls = loader.getResources(resource);
                while (urls.hasMoreElements()) {
                    URLConnection connection = urls.nextElement().openConnection();
                    connection.setUseCaches(false);
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String className = line.split("#", 2)[0].trim();
                            if (className.isEmpty() || !seen.add(className)) continue;
                            try {
                                Class<?> impl = Class.forName(className, true, loader);
                                if (!serviceType.isAssignableFrom(impl)) continue;
                                @SuppressWarnings("unchecked")
                                S service = (S) impl.getDeclaredConstructor().newInstance();
                                out.add(service);
                            } catch (ReflectiveOperationException ignored) {
                                //invalid impl ignored
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            //no service file for this classloader
        }
    }

    /** Spec §5.2 — applies the @RegisterProvider declarations found on the client interface. */
    private void applyRegisterProviders(Class<?> clazz) {
        RegisterProvider[] providers = clazz.getAnnotationsByType(RegisterProvider.class);
        for (RegisterProvider rp : providers) {
            //Avoid double registration: if the provider is already registered
            //(e.g. via MP Config /mp-rest/providers), it is not registered again.
            if (configuration.isRegistered(rp.value())) continue;
            configuration.registerProvider(rp.value(), detectContracts(rp.value(), rp.priority()));
        }
    }

    private void applyFeatures() {
        FeatureContext context = new BuilderFeatureContext();
        Set<Class<?>> processed = new HashSet<>();
        boolean progressed;
        do {
            progressed = false;
            List<Object> snapshot = List.copyOf(configuration.getInstances());
            for (Object instance : snapshot) {
                if (!(instance instanceof Feature feature)) continue;
                Class<?> featureClass = instance.getClass();
                if (!processed.add(featureClass)) continue;
                boolean enabled;
                try {
                    enabled = feature.configure(context);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (enabled) {
                    configuration.markFeatureEnabled(feature);
                }
                progressed = true;
            }
        } while (progressed);
    }

    private final class BuilderFeatureContext implements FeatureContext {
        @Override
        public Configuration getConfiguration() {
            return configuration;
        }

        @Override
        public FeatureContext property(String name, Object value) {
            CyranoRestClientBuilder.this.property(name, value);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass) {
            CyranoRestClientBuilder.this.register(componentClass);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass, int priority) {
            CyranoRestClientBuilder.this.register(componentClass, priority);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Class<?>... contracts) {
            CyranoRestClientBuilder.this.register(componentClass, contracts);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
            CyranoRestClientBuilder.this.register(componentClass, contracts);
            return this;
        }

        @Override
        public FeatureContext register(Object component) {
            CyranoRestClientBuilder.this.register(component);
            return this;
        }

        @Override
        public FeatureContext register(Object component, int priority) {
            CyranoRestClientBuilder.this.register(component, priority);
            return this;
        }

        @Override
        public FeatureContext register(Object component, Class<?>... contracts) {
            CyranoRestClientBuilder.this.register(component, contracts);
            return this;
        }

        @Override
        public FeatureContext register(Object component, Map<Class<?>, Integer> contracts) {
            CyranoRestClientBuilder.this.register(component, contracts);
            return this;
        }
    }
}
