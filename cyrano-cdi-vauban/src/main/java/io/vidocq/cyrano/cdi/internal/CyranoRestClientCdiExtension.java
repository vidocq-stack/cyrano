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
 * Build Compatible Extension Cyrano — découverte automatique des interfaces
 * annotées {@link RegisterRestClient} et synthèse d'un bean CDI par interface.
 *
 * <p>Phases :</p>
 * <ul>
 *   <li><strong>{@link Enhancement}</strong> avec
 *       {@code withAnnotations = RegisterRestClient.class} : collecte chaque
 *       interface {@code @RegisterRestClient} rencontrée pendant le scan du
 *       container. Validation immédiate : doit être une interface, doit avoir
 *       au moins une source de base URI (annotation ou {@code configKey}).</li>
 *   <li><strong>{@link Synthesis}</strong> : pour chaque interface collectée,
 *       enregistre un {@code SyntheticBean} qualifié
 *       {@link RestClient @RestClient}, dans le scope déduit de l'annotation
 *       de portée portée par l'interface (ou {@link Dependent} par défaut, spec §6.3).</li>
 * </ul>
 *
 * <p>Spec MP Rest Client 4.0 :</p>
 * <ul>
 *   <li>§6.1 — « CDI implementations must search for interfaces annotated
 *       with @RegisterRestClient and create a CDI bean for each one ».</li>
 *   <li>§6.2 — l'injection {@code @Inject @RestClient X x} doit recevoir un
 *       proxy fonctionnellement équivalent à
 *       {@code RestClientBuilder.newBuilder().build(X.class)}.</li>
 *   <li>§6.3 — scope par défaut {@link Dependent} ; surchargeable par une
 *       annotation de scope portée par l'interface.</li>
 * </ul>
 *
 * <p>Découverte : {@code META-INF/services/...BuildCompatibleExtension} +
 * {@code provides ... with} dans {@code module-info.java}.</p>
 */
public class CyranoRestClientCdiExtension implements BuildCompatibleExtension {

    /** Interfaces collectées en phase {@code @Enhancement}, dédupliquées par FQN. */
    private final Map<String, DiscoveredInterface> discovered = new LinkedHashMap<>();

    /**
     * Phase {@code @Enhancement} (CDI Lite §3.8) — fires sur chaque classe
     * portant {@link RegisterRestClient}. On vérifie que la cible est une
     * interface et qu'au moins une base URI sera résolvable au runtime.
     */
    @Enhancement(types = Object.class, withAnnotations = RegisterRestClient.class, withSubtypes = true)
    public void discoverRegisterRestClient(ClassConfig classConfig, Messages messages) {
        ClassInfo info = classConfig.info();
        if (!info.isInterface()) {
            if (messages != null) {
                messages.error(
                        "Cyrano CDI : @RegisterRestClient n'est applicable qu'à une interface, "
                                + "or '" + info.name() + "' n'en est pas une (spec MP Rest Client 4.0 §3.1)",
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
        // §5 / §6.3 — scope peut être surchargé par MP Config : <fqn>/mp-rest/scope ou <configKey>/mp-rest/scope
        String configScope = resolveConfigScope(info.name(), configKey);
        String scope = configScope != null ? configScope : annotationScope;

        discovered.putIfAbsent(info.name(), new DiscoveredInterface(info.name(), baseUri, configKey, scope));
    }

    /**
     * Phase {@code @Synthesis} — produit un {@code SyntheticBean} par interface
     * collectée. Qualifieur {@link RestClient}, scope déduit de l'interface,
     * creator {@link CyranoRestClientSyntheticCreator}.
     */
    @Synthesis
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void synthesizeRestClientBeans(SyntheticComponents components) {
        for (DiscoveredInterface d : discovered.values()) {
            Class<?> iface = loadOrNull(d.fqn());
            if (iface == null) {
                // Si l'interface n'est pas chargeable côté container, on ignore
                // silencieusement — Vauban a déjà signalé l'erreur classpath.
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
     * Détecte une annotation de scope CDI sur l'interface (spec §6.3).
     * Retourne le FQN du scope, ou {@code null} si aucun (→ {@link Dependent}).
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
     * Résout le scope depuis MP Config (spec §5 / §6.3) :
     * {@code <fqn>/mp-rest/scope} puis {@code <configKey>/mp-rest/scope}.
     * Retourne le FQN du scope si trouvé, {@code null} sinon.
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
            // fallback suivant
        }
        try {
            return Class.forName(fqn, false, self);
        } catch (ClassNotFoundException ignored) {
            // fallback final
        }
        try {
            return Class.forName(fqn);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** Métadonnées d'interface collectées en phase Enhancement. */
    private record DiscoveredInterface(String fqn, String baseUri, String configKey, String scope) {}
}

