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
 * Manages the life cycle of the Vauban CDI container in the TCK Arquillian Runner.
 * Called from {@link CyranoDeployableContainer#deploy(Archive)} /
 * {@link CyranoDeployableContainer#undeploy(Archive)}.
 *
 * <p>Each Arquillian deployment (one ShrinkWrap archive per TCK test class):</p>
 * <ol>
 *   <li>Extracts {@code META-INF/microprofile-config.properties} from the archive;</li>
 *   <li>Configures {@link TckConfigBridge} with the properties (URL redirected to WireMock);</li>
 *   <li>Extracts the archive class names (already on the classpath in Local mode);</li>
 *   <li>Starts a Vauban container with {@link CyranoRestClientCdiExtension} + these classes.</li>
 * </ol>
 *
 * <p>CDI Lite / Vauban constraint: {@code addBeanClass} accepts interfaces (Vauban filters
 * them out when creating managed beans, but the {@code @Enhancement} BCE can still inspect
 * them through the scan of the internal index). We therefore pass all archive classes,
 * interfaces included.</p>
 */
final class VaubanTckBootstrap {

    private VaubanTckBootstrap() {}

    /**
     * Start a new Vauban container for the given archive.
     *
     * @param archive the ShrinkWrap archive provided by the TCK test's {@code @Deployment}
     */
    private static ClassLoader previousTccl;
    private static java.net.URLClassLoader deploymentLoader;
    private static java.nio.file.Path deploymentRoot;

    static void deploy(Archive<?> archive) {
        // 0. Materialize the archive resources (non-class assets) on disk and
        // install a deployment class loader as TCCL: the ssl/** TCK suites
        // configure trust/key stores as classpath:/META-INF/... locations
        // packed inside the ShrinkWrap archive, and AbstractSslTest reads
        // META-INF/certificates-dir.txt through the context class loader.
        materializeResources(archive);

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
     * Copies every non-class asset of the archive (WEB-INF/classes normalized
     * to the root) into a temp directory and installs a URLClassLoader over it
     * as the context class loader for the deployment's lifetime.
     */
    private static void materializeResources(Archive<?> archive) {
        try {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("cyrano-tck-deployment-");
            for (var entry : archive.getContent().entrySet()) {
                var node = entry.getValue();
                if (node == null || node.getAsset() == null) {
                    continue;
                }
                String relative = entry.getKey().get();
                if (relative.startsWith("/")) {
                    relative = relative.substring(1);
                }
                if (relative.startsWith("WEB-INF/classes/")) {
                    relative = relative.substring("WEB-INF/classes/".length());
                }
                if (relative.isEmpty() || relative.endsWith(".class")) {
                    continue;
                }
                java.nio.file.Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root)) {
                    continue;
                }
                java.nio.file.Files.createDirectories(target.getParent());
                try (var in = node.getAsset().openStream()) {
                    java.nio.file.Files.copy(in, target,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            deploymentRoot = root;
            previousTccl = Thread.currentThread().getContextClassLoader();
            deploymentLoader = new java.net.URLClassLoader(
                    "cyrano-tck-deployment", new java.net.URL[] {root.toUri().toURL()}, previousTccl);
            Thread.currentThread().setContextClassLoader(deploymentLoader);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot materialize the TCK archive resources", e);
        }
    }

    /**
     * Stop the current Vauban container and clean the config properties system.
     */
    static void undeploy() {
        TckConfigBridge.clear();
        if (previousTccl != null) {
            Thread.currentThread().setContextClassLoader(previousTccl);
            previousTccl = null;
        }
        if (deploymentLoader != null) {
            try { deploymentLoader.close(); } catch (Exception ignored) {}
            deploymentLoader = null;
        }
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
     * Extracts {@code META-INF/microprofile-config.properties} from the archive.
     * Supports:
     * <ul>
     *   <li>JavaArchive: {@code /META-INF/microprofile-config.properties}</li>
     *   <li>WebArchive classes: {@code /WEB-INF/classes/META-INF/...}</li>
     *   <li>WebArchive library: {@code /WEB-INF/lib/*.jar/META-INF/...} (embedded ShrinkWrap archive)</li>
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
     * Extract all application classes from the archive (interfaces included — ECB)
     * {@code @Enhancement} from Cyrano needs it to discover {@code @RegisterRestClient}).
     * Classes {@code module-info} are excluded.
     *
     * <p>Supports three layouts:</p>
     * <ul>
     *   <li>JavaArchive: {@code /org/example/MyClass.class} directly at the root;</li>
     *   <li>WebArchive/classes: {@code /WEB-INF/classes/org/example/MyClass.class};</li>
     *   <li>WebArchive/lib: ShrinkWrap archive embedded in {@code /WEB-INF/lib/*.jar}.</li>
     * </ul>
     *
     * <p>In Local Arquillian mode, the archive classes are already present on the test
     * JVM classpath — {@code Class.forName} returns them directly via their context
     * {@link ClassLoader}.</p>
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
                //Class not available on test classpath — skip silently
            }
        }
    }
}
