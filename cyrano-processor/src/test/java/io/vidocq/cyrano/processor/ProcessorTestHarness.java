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
package io.vidocq.cyrano.processor;

import io.vidocq.cyrano.spi.gen.ClientProxyFactory;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * In-process javax.tools.JavaCompiler harness with cyrano-processor on the
 * annotation-processor path — same zero-dep approach as cassini's processor tests.
 * Shared by the processor test classes.
 */
final class ProcessorTestHarness {

    record Compilation(URLClassLoader loader, File outputDir,
                       List<javax.tools.Diagnostic<? extends JavaFileObject>> diagnostics) {

        ClientProxyFactory factoryOf(String ifaceFqn) throws Exception {
            return (ClientProxyFactory) loader.loadClass(ifaceFqn + "$$CyranoClient$Factory")
                    .getDeclaredConstructor().newInstance();
        }
    }

    private ProcessorTestHarness() {
        // utility
    }

    static File writeSource(Path tempDir, String relativePath, String content) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file.toFile();
    }

    static Compilation compileWithProcessor(Path tempDir, File... sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "javax.tools.JavaCompiler not available in this JDK");

        File outputDir = Files.createDirectories(tempDir.resolve("classes-" + System.nanoTime())).toFile();

        // Effective classpath: java.class.path + URLClassLoader chain + named module layer
        // (covers both classpath and module-path Surefire executions).
        List<File> cpFiles = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank()) cpFiles.add(new File(entry));
        }
        ClassLoader cl = ProcessorTestHarness.class.getClassLoader();
        while (cl != null) {
            if (cl instanceof URLClassLoader ucl) {
                for (java.net.URL url : ucl.getURLs()) {
                    if ("file".equals(url.getProtocol())) cpFiles.add(new File(url.toURI()));
                }
            }
            cl = cl.getParent();
        }
        ModuleLayer layer = ProcessorTestHarness.class.getModule().getLayer();
        if (layer != null) {
            layer.configuration().modules().forEach(rm -> rm.reference().location().ifPresent(uri -> {
                if ("file".equals(uri.getScheme())) cpFiles.add(new File(uri));
            }));
        }
        List<File> dedupCp = cpFiles.stream().distinct().filter(File::exists).toList();

        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(diags, Locale.ROOT, null);
        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir));
        fm.setLocation(StandardLocation.CLASS_PATH, dedupCp);
        fm.setLocation(StandardLocation.ANNOTATION_PROCESSOR_PATH, dedupCp);

        boolean success = compiler.getTask(null, fm, diags,
                List.of("--release", "25", "-proc:full"), null,
                fm.getJavaFileObjects(sources)).call();
        if (!success) {
            StringBuilder sb = new StringBuilder("Compilation failed:\n");
            for (var d : diags.getDiagnostics()) {
                sb.append(d.getKind()).append(": ").append(d.getMessage(Locale.ROOT)).append('\n');
            }
            fail(sb.toString());
        }
        fm.close();
        return new Compilation(
                new URLClassLoader(new java.net.URL[] { outputDir.toURI().toURL() },
                        ProcessorTestHarness.class.getClassLoader()),
                outputDir, diags.getDiagnostics());
    }
}
