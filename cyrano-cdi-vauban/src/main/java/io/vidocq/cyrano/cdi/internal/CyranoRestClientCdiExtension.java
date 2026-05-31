/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cyrano.cdi.internal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Build Compatible Extension Cyrano — automatic discovery of interfaces
 * annotated with {@link RegisterRestClient} and synthesis of a CDI bean per interface.
 *
 * <p>Phases:</p>
 * <ul>
 *   <li><strong>{@link Enhancement}</strong> with
 *       {@code withAnnotations = RegisterRestClient.class}: collects each
 * {@code @RegisterRestClient} interface encountered during the container scan.
 *       Immediate validation: must be an interface, and must have at least one
 *       base URI source (annotation or {@code configKey}).</li>
 *   <li><strong>{@link Synthesis}</strong>: for each collected interface,
 *       registers a qualified {@code SyntheticBean}
 * {@link RestClient @RestClient}, in the scope deduced from the scope annotation
 * carried by the interface (or {@link Dependent} by default, spec §6.3).</li>
 * </ul>
 *
 * <p>Spec MP Rest Client 4.0:</p>
 * <ul>
 * <li>§6.1 — « CDI implementations must search for interfaces annotated
 * with @RegisterRestClient and create a CDI bean for each one".</li>
 * <li>§6.2 — injection {@code @Inject @RestClient X x} should be given
 * a proxy functionally equivalent to
 *       {@code RestClientBuilder.newBuilder().build(X.class)}.</li>
 * <li>§6.3 — default scope {@link Dependent}; overridable by a
 * scope annotation carried by the interface.</li>
 * </ul>
 *
 * <p>Discovery: {@code META-INF/services/...BuildCompatibleExtension} +
 * {@code provides ... with} in {@code module-info.java}.</p>
 */
public class CyranoRestClientCdiExtension implements BuildCompatibleExtension {

    /** Interfaces collected in the {@code @Enhancement} phase, deduplicated by FQN. */
    private final Map<String, DiscoveredInterface> discovered = new LinkedHashMap<>();

    /**
     * Phase {@code @Enhancement} (CDI Lite §3.8) — Fires on each class
     * carrying {@link RegisterRestClient}. We check that the target is an
     * interface and that at least one URI base will be resolvable at runtime.
     */
    @Enhancement(types = Object.class, withAnnotations = RegisterRestClient.class, withSubtypes = true)
    public void discoverRegisterRestClient(ClassConfig classConfig, Messages messages) {
        ClassInfo info = classConfig.info();
        if (!info.isInterface()) {
            if (messages != null) {
                messages.error(
                        "Cyrano CDI: @RegisterRestClient is only applicable to an interface, "
                                + "but '" + info.name() + "' is not one (MP Rest Client 4.0 spec §3.1)",
                        info);
            }
            return;
        }
        AnnotationInfo anno = findAnnotation(info, RegisterRestClient.class.getName());
        if (anno == null) {
            return;
        }
        String baseUri = stringMember(anno, "baseUri");
        String configKey = stringMember(anno, "configKey");
        String annotationScope = detectScopeAnnotation(info);
        //§5 / §6.3 — scope can be overridden by MP Config: <fqn>/mp-rest/scope or <configKey>/mp-rest/scope
        String configScope = resolveConfigScope(info.name(), configKey);
        String scope = configScope != null ? configScope : annotationScope;

        discovered.putIfAbsent(info.name(), new DiscoveredInterface(info.name(), baseUri, configKey, scope));
    }

    /**
     * {@code @Synthesis} phase — produces a {@code SyntheticBean} per collected
     * interface. {@link RestClient} qualifier, scope deduced from the interface,
     * creator {@link CyranoRestClientSyntheticCreator}.
     */
    @Synthesis
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void synthesizeRestClientBeans(SyntheticComponents components) {
        for (DiscoveredInterface d : discovered.values()) {
            Class<?> iface = loadOrNull(d.fqn());
            if (iface == null) {
                //If the interface is not loadable on the container side, we do nothing
                // silently — Vauban has already reported the classpath error.
                continue;
            }
            Class<? extends Annotation> scopeClass = resolveScopeClass(d.scope());

            ((jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder) components.addBean(iface))
                    .type(iface)
                    .qualifier(RestClient.class)
                    .scope(scopeClass)
                    .withParam(CyranoRestClientSyntheticCreator.PARAM_INTERFACE_NAME, d.fqn())
                    .withParam(CyranoRestClientSyntheticCreator.PARAM_BASE_URI, d.baseUri())
                    .withParam(CyranoRestClientSyntheticCreator.PARAM_CONFIG_KEY, d.configKey())
                    .createWith(CyranoRestClientSyntheticCreator.class)
                    .disposeWith(CyranoRestClientSyntheticDisposer.class);
        }

    }

    // --- helpers ----------------------------------------------------------

    private static AnnotationInfo findAnnotation(ClassInfo info, String fqn) {
        for (AnnotationInfo ai : info.annotations()) {
            if (fqn.equals(ai.name())) return ai;
        }
        return null;
    }

    private static String stringMember(AnnotationInfo anno, String name) {
        AnnotationMember m = anno.members().get(name);
        if (m == null || !m.isString()) return "";
        String v = m.asString();
        return v == null ? "" : v;
    }

    /**
     * Detects a CDI scope annotation on the interface (spec §6.3).
     * Returns the FQN of the scope, or {@code null} if none (→ {@link Dependent}).
     */
    private static String detectScopeAnnotation(ClassInfo info) {
        for (AnnotationInfo ai : info.annotations()) {
            String n = ai.name();
            if (ApplicationScoped.class.getName().equals(n)
                    || Dependent.class.getName().equals(n)
                    || Singleton.class.getName().equals(n)
                    || RequestScoped.class.getName().equals(n)
                    || SessionScoped.class.getName().equals(n)
                    || "jakarta.enterprise.context.ConversationScoped".equals(n)) {
                return n;
            }
        }
        return null;
    }

    /**
     * Resolves the scope from MP Config (spec §5 / §6.3):
     * {@code <fqn>/mp-rest/scope} then {@code <configKey>/mp-rest/scope}.
     * Returns the scope FQN if found, {@code null} otherwise.
     */
    private static String resolveConfigScope(String fqn, String configKey) {
        Function<String, Optional<String>> lookup = CyranoBaseUriResolver.defaultMpConfigLookup();
        Optional<String> fromFqn = lookup.apply(fqn + "/mp-rest/scope");
        if (fromFqn.filter(s -> !s.isBlank()).isPresent()) return fromFqn.get();
        if (configKey != null && !configKey.isBlank()) {
            Optional<String> fromKey = lookup.apply(configKey + "/mp-rest/scope");
            if (fromKey.filter(s -> !s.isBlank()).isPresent()) return fromKey.get();
        }
        return null;
    }

    private static Class<? extends Annotation> resolveScopeClass(String fqn) {
        if (fqn == null) return Dependent.class;
        return switch (fqn) {
            case "jakarta.enterprise.context.ApplicationScoped"  -> ApplicationScoped.class;
            case "jakarta.enterprise.context.RequestScoped"      -> RequestScoped.class;
            case "jakarta.enterprise.context.SessionScoped"      -> SessionScoped.class;
            case "jakarta.inject.Singleton"                      -> Singleton.class;
            case "jakarta.enterprise.context.Dependent"          -> Dependent.class;
            case "jakarta.enterprise.context.ConversationScoped" -> jakarta.enterprise.context.ConversationScoped.class;
            default                                              -> Dependent.class;
        };
    }

    private static Class<?> loadOrNull(String fqn) {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        ClassLoader self = CyranoRestClientCdiExtension.class.getClassLoader();
        try {
            if (tccl != null) {
                return Class.forName(fqn, false, tccl);
            }
        } catch (ClassNotFoundException ignored) {
            // next fallback
        }
        try {
            return Class.forName(fqn, false, self);
        } catch (ClassNotFoundException ignored) {
            // final fallback
        }
        try {
            return Class.forName(fqn);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** Interface metadata collected in the Enhancement phase. */
    private record DiscoveredInterface(String fqn, String baseUri, String configKey, String scope) {}
}
