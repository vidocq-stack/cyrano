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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Packs test classes into a jar of their own and loads it as a module in a child layer of the boot
 * layer, so the code under test meets a service provider that lives in another module — the way an
 * application or another brick (humboldt-rest's {@code RestClientListener}) provides one.
 *
 * <p>The jar is an automatic module: the module system derives its {@code provides} from its
 * {@code META-INF/services} files, and the consumer's own {@code uses} decides whether
 * {@link java.util.ServiceLoader} may look the service up at all.</p>
 */
public final class ProviderModule {

    private final Path dir;
    private final String name;
    private final Map<Class<?>, List<Class<?>>> services = new LinkedHashMap<>();
    private final List<Class<?>> classes = new ArrayList<>();

    private ProviderModule(Path dir, String name) {
        this.dir = dir;
        this.name = name;
    }

    public static ProviderModule named(Path dir, String name) {
        return new ProviderModule(dir, name);
    }

    public ProviderModule provides(Class<?> service, Class<?>... providers) {
        services.put(service, List.of(providers));
        return this;
    }

    /** Further classes of the module, besides its providers. */
    public ProviderModule with(Class<?>... types) {
        classes.addAll(List.of(types));
        return this;
    }

    /** Writes the jar and defines it in a new layer whose parent is the boot layer. */
    public ModuleLayer layer() {
        Path jar = dir.resolve(name + ".jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", name);
        try (OutputStream file = Files.newOutputStream(jar);
             var out = new JarOutputStream(file, manifest)) {
            for (Class<?> type : classes) {
                copyClass(type, out);
            }
            for (var entry : services.entrySet()) {
                var lines = new StringBuilder();
                for (Class<?> provider : entry.getValue()) {
                    lines.append(provider.getName()).append('\n');
                    copyClass(provider, out);
                }
                out.putNextEntry(new JarEntry("META-INF/services/" + entry.getKey().getName()));
                out.write(lines.toString().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var parent = ModuleLayer.boot();
        var configuration = parent.configuration().resolve(ModuleFinder.of(jar), ModuleFinder.of(), Set.of(name));
        return parent.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
    }

    private static void copyClass(Class<?> type, JarOutputStream out) throws IOException {
        String path = type.getName().replace('.', '/') + ".class";
        String simple = path.substring(path.lastIndexOf('/') + 1);
        try (InputStream in = type.getResourceAsStream(simple)) {
            if (in == null) {
                throw new IOException("class file not found: " + path);
            }
            out.putNextEntry(new JarEntry(path));
            in.transferTo(out);
            out.closeEntry();
        }
    }
}
