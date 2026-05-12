/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.vidocq.cyrano.tck.arquillian;

import io.vidocq.cyrano.cdi.internal.CyranoRestClientCdiExtension;
import io.vidocq.cyrano.cdi.internal.CyranoRestClientInstanceProducer;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.asset.ArchiveAsset;
import org.jboss.shrinkwrap.api.asset.Asset;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Gère le cycle de vie du container Vauban CDI dans le runner TCK Arquillian.
 * Appelé depuis {@link CyranoDeployableContainer#deploy(Archive)} /
 * {@link CyranoDeployableContainer#undeploy(Archive)}.
 *
 * <p>Chaque déploiement Arquillian (une ShrinkWrap archive par classe de test TCK) :
 * <ol>
 *   <li>Extrait {@code META-INF/microprofile-config.properties} de l'archive ;</li>
 *   <li>Configure {@link TckConfigBridge} avec les propriétés (URL redirigées vers WireMock) ;</li>
 *   <li>Extrait les noms de classes de l'archive (déjà sur le classpath en mode Local) ;</li>
 *   <li>Démarre un container Vauban avec {@link CyranoRestClientCdiExtension} + ces classes.</li>
 * </ol>
 * </p>
 *
 * <p>Contrainte CDI Lite / Vauban : {@code addBeanClass} accepte les interfaces (Vauban les
 * filtre pour la création de beans gérés, mais la BCE {@code @Enhancement} peut les inspecter
 * via le scan de l'index interne). On passe donc toutes les classes de l'archive, interfaces
 * comprises.</p>
 */
final class VaubanTckBootstrap {

    private VaubanTckBootstrap() {}

    /**
     * Démarre un nouveau container Vauban pour l'archive donnée.
     *
     * @param archive l'archive ShrinkWrap fournie par {@code @Deployment} du test TCK
     */
    static void deploy(Archive<?> archive) {
        // 1. Extract config properties from the archive
        Properties configProps = extractConfig(archive);

        // 2. Configure TckConfigBridge (redirects mp-rest/url values to WireMock)
        String wireMockUrl = "http://127.0.0.1:" + WireMockTestBackend.PORT;
        TckConfigBridge.setProperties(configProps, wireMockUrl);
        TckConfigBridge.exportToSystemProperties();

        // 3. Shutdown any existing container before starting a new one
        VaubanContainer existing = VaubanContainer.current();
        if (existing != null && existing.isRunning()) {
            try { existing.close(); } catch (Exception ignored) {}
        }

        // 4. Extract bean classes from the archive (all classes, including interfaces)
        List<Class<?>> beanClasses = extractBeanClasses(archive);

        // 5. Boot new Vauban container with CyranoRestClientCdiExtension + archive classes
        var builder = VaubanContainer.builder()
                .addBeanClass(CyranoRestClientCdiExtension.class)
                .addBeanClass(CyranoRestClientInstanceProducer.class);
        for (Class<?> c : beanClasses) {
            builder.addBeanClass(c);
        }
        builder.build();

        System.err.println("[CyranoTCK] Vauban CDI container started for archive '"
                + archive.getName() + "' — " + beanClasses.size() + " class(es) registered");
    }

    /**
     * Arrête le container Vauban courant et nettoie les system properties de config.
     */
    static void undeploy() {
        TckConfigBridge.clear();
        VaubanContainer existing = VaubanContainer.current();
        if (existing != null && existing.isRunning()) {
            try { existing.close(); } catch (Exception ignored) {}
        }
        System.err.println("[CyranoTCK] Vauban CDI container stopped");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Extrait {@code META-INF/microprofile-config.properties} de l'archive.
     * Supporte :
     * <ul>
     *   <li>JavaArchive : {@code /META-INF/microprofile-config.properties}</li>
     *   <li>WebArchive classes : {@code /WEB-INF/classes/META-INF/...}</li>
     *   <li>WebArchive library : {@code /WEB-INF/lib/*.jar/META-INF/...} (archives ShrinkWrap imbriquées)</li>
     * </ul>
     */
    private static Properties extractConfig(Archive<?> archive) {
        Properties props = new Properties();
        // Direct paths (JavaArchive or WebArchive/classes layout)
        Node node = archive.get("/META-INF/microprofile-config.properties");
        if (node == null) {
            node = archive.get("/WEB-INF/classes/META-INF/microprofile-config.properties");
        }
        if (node != null) {
            Asset asset = node.getAsset();
            if (asset != null) {
                try (InputStream is = asset.openStream()) {
                    props.load(is);
                } catch (Exception ignored) {}
            }
            if (!props.isEmpty()) return props;
        }
        // WebArchive library layout: search inside WEB-INF/lib/*.jar nested archives
        props = extractConfigFromLibraries(archive);
        return props;
    }

    private static Properties extractConfigFromLibraries(Archive<?> archive) {
        Properties props = new Properties();
        Node libDir = archive.get("/WEB-INF/lib");
        if (libDir == null) return props;
        for (Node child : libDir.getChildren()) {
            Asset asset = child.getAsset();
            if (!(asset instanceof ArchiveAsset archiveAsset)) continue;
            Archive<?> nested = archiveAsset.getArchive();
            Node configNode = nested.get("/META-INF/microprofile-config.properties");
            if (configNode == null) continue;
            Asset configAsset = configNode.getAsset();
            if (configAsset == null) continue;
            try (InputStream is = configAsset.openStream()) {
                props.load(is);
                if (!props.isEmpty()) return props;
            } catch (Exception ignored) {}
        }
        return props;
    }

    /**
     * Extrait toutes les classes applicatives de l'archive (interfaces incluses — la BCE
     * {@code @Enhancement} de Cyrano en a besoin pour découvrir les {@code @RegisterRestClient}).
     * Les classes {@code module-info} sont exclues.
     *
     * <p>Supporte trois layouts :</p>
     * <ul>
     *   <li>JavaArchive : {@code /org/example/MyClass.class} directement à la racine ;</li>
     *   <li>WebArchive/classes : {@code /WEB-INF/classes/org/example/MyClass.class} ;</li>
     *   <li>WebArchive/lib : archives ShrinkWrap imbriquées dans {@code /WEB-INF/lib/*.jar}.</li>
     * </ul>
     *
     * <p>En mode Local Arquillian, les classes de l'archive sont déjà présentes sur le
     * classpath de la JVM de test — {@code Class.forName} retrouve directement leur
     * {@link ClassLoader} contexte.</p>
     */
    private static List<Class<?>> extractBeanClasses(Archive<?> archive) {
        var classes = new ArrayList<Class<?>>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        collectClassesFromArchive(archive, cl, classes, false);
        return classes;
    }

    private static void collectClassesFromArchive(Archive<?> archive, ClassLoader cl,
                                                   List<Class<?>> classes, boolean insideLib) {
        for (var entry : archive.getContent().entrySet()) {
            String path = entry.getKey().get();

            // Recurse into nested archives (WEB-INF/lib/*.jar)
            Asset asset = entry.getValue().getAsset();
            if (asset instanceof ArchiveAsset archiveAsset
                    && (path.startsWith("/WEB-INF/lib/") || insideLib)) {
                collectClassesFromArchive(archiveAsset.getArchive(), cl, classes, true);
                continue;
            }

            if (!path.endsWith(".class")) continue;
            if (path.contains("module-info")) continue;

            // Strip leading slash
            String stripped = path.startsWith("/") ? path.substring(1) : path;
            // Strip WEB-INF/classes/ prefix if present (WebArchive layout)
            if (stripped.startsWith("WEB-INF/classes/")) {
                stripped = stripped.substring("WEB-INF/classes/".length());
            }
            String className = stripped.replace('/', '.').replace(".class", "");
            if (className.isBlank()) continue;

            try {
                Class<?> clazz = Class.forName(className, false, cl);
                classes.add(clazz);
            } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                // Class not available on test classpath — skip silently
            }
        }
    }
}
