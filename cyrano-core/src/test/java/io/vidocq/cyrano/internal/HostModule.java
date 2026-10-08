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
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Packs test classes into an explicit application module — a jar with a real
 * {@code module-info.class}, written with the Class-File API — and defines it in a child layer of the
 * boot layer. Unlike the automatic module of {@link ProviderModule}, which reads every module and
 * opens every package, this module reads and opens only what its descriptor says, as an application
 * module does.
 */
public final class HostModule {

    private final Path dir;
    private final String name;
    private final Set<String> requires = new LinkedHashSet<>();
    private final List<String[]> opens = new ArrayList<>();
    private final List<Class<?>> classes = new ArrayList<>();

    private HostModule(Path dir, String name) {
        this.dir = dir;
        this.name = name;
    }

    public static HostModule named(Path dir, String name) {
        return new HostModule(dir, name);
    }

    public HostModule requires(String module) {
        requires.add(module);
        return this;
    }

    /** {@code opens <pkg> to <targets>}. */
    public HostModule opens(String pkg, String... targets) {
        String[] entry = new String[targets.length + 1];
        entry[0] = pkg;
        System.arraycopy(targets, 0, entry, 1, targets.length);
        opens.add(entry);
        return this;
    }

    public HostModule with(Class<?>... types) {
        classes.addAll(List.of(types));
        return this;
    }

    /** Writes the jar and defines the module in a new layer whose parent is the boot layer. */
    public ModuleLayer layer() {
        Path jar = dir.resolve(name + ".jar");
        try (OutputStream file = Files.newOutputStream(jar); var out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry("module-info.class"));
            out.write(moduleInfo());
            out.closeEntry();
            for (Class<?> type : classes) {
                String path = type.getName().replace('.', '/') + ".class";
                try (InputStream in = type.getResourceAsStream(path.substring(path.lastIndexOf('/') + 1))) {
                    if (in == null) {
                        throw new IOException("class file not found: " + path);
                    }
                    out.putNextEntry(new JarEntry(path));
                    in.transferTo(out);
                    out.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var parent = ModuleLayer.boot();
        var configuration = parent.configuration().resolve(ModuleFinder.of(jar), ModuleFinder.of(), Set.of(name));
        return parent.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
    }

    private byte[] moduleInfo() {
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(name), module -> {
            module.requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null);
            for (String required : requires) {
                module.requires(ModuleDesc.of(required), 0, null);
            }
            for (String[] entry : opens) {
                ModuleDesc[] targets = new ModuleDesc[entry.length - 1];
                for (int i = 1; i < entry.length; i++) {
                    targets[i - 1] = ModuleDesc.of(entry[i]);
                }
                module.opens(PackageDesc.of(entry[0]), 0, targets);
            }
        }));
    }
}
