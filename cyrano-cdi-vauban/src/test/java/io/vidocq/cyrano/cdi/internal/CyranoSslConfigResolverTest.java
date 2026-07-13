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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec MP Rest Client 4.0 §5.6 — key-store locations configured through MP
 * Config accept {@code file:} URLs and {@code classpath:} resources (the TCK
 * packs its PKCS12 stores inside the deployment archive, visible through the
 * context class loader).
 */
class CyranoSslConfigResolverTest {

    static Path certsDir;

    @BeforeAll
    static void generateStore() throws Exception {
        certsDir = Files.createTempDirectory("cyrano-ssl-config-");
        Process p = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-keyalg", "RSA", "-keysize", "2048",
                "-alias", "cfg", "-dname", "CN=config-test",
                "-validity", "1", "-storetype", "PKCS12",
                "-keystore", certsDir.resolve("cfg.p12").toString(),
                "-storepass", "password")).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool failed:\n" + out);
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (var files = Files.walk(certsDir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }
    }

    @Test
    void loads_a_store_from_a_file_url_spec_section5_6() {
        KeyStore store = CyranoSslConfigResolver.loadStore(
                certsDir.resolve("cfg.p12").toUri().toString(), "PKCS12", "password");
        assertNotNull(store);
    }

    @Test
    void loads_a_store_from_the_classpath_spec_section5_6() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader deploymentLike = new URLClassLoader(
                new URL[] {certsDir.toUri().toURL()}, previous)) {
            Thread.currentThread().setContextClassLoader(deploymentLike);
            KeyStore store = CyranoSslConfigResolver.loadStore(
                    "classpath:/cfg.p12", "PKCS12", "password");
            assertNotNull(store);
            assertEquals("PKCS12", store.getType());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void missing_classpath_resource_fails_with_a_clear_message() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CyranoSslConfigResolver.loadStore("classpath:/does-not-exist.p12", "PKCS12", "pwd"));
        assertNotNull(failure.getCause());
    }
}
