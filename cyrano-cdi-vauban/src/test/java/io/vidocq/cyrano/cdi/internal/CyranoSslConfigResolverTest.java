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

import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final String FQN = "com.example.SecureApi";
    private static final String CONFIG_KEY = "secure";
    private static final String PASSWORD = "password";

    /** A verifier named in {@code <fqn>/mp-rest/hostnameVerifier}. */
    public static final class AcceptAllVerifier implements HostnameVerifier {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    }

    private static Function<String, Optional<String>> lookup(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key));
    }

    /** A {@link RestClientBuilder} double recording the SSL options it receives. */
    private static RestClientBuilder recordingBuilder(List<Object> received) {
        return (RestClientBuilder) Proxy.newProxyInstance(CyranoSslConfigResolverTest.class.getClassLoader(),
                new Class<?>[] {RestClientBuilder.class}, (proxy, method, args) -> {
                    received.add(method.getName());
                    received.addAll(List.of(args));
                    return proxy;
                });
    }

    @Test
    void applies_trust_store_key_store_and_hostname_verifier_spec_section5_6() {
        String location = certsDir.resolve("cfg.p12").toUri().toString();
        List<Object> received = new ArrayList<>();

        CyranoSslConfigResolver.applySslConfig(FQN, CONFIG_KEY, lookup(Map.of(
                FQN + "/mp-rest/trustStore", location,
                CONFIG_KEY + "/mp-rest/trustStoreType", "PKCS12",
                CONFIG_KEY + "/mp-rest/trustStorePassword", PASSWORD,
                CONFIG_KEY + "/mp-rest/keyStore", location,
                FQN + "/mp-rest/keyStoreType", "PKCS12",
                FQN + "/mp-rest/keyStorePassword", PASSWORD,
                FQN + "/mp-rest/hostnameVerifier", AcceptAllVerifier.class.getName())), recordingBuilder(received));

        assertEquals(List.of("trustStore", "keyStore", PASSWORD, "hostnameVerifier"),
                received.stream().filter(String.class::isInstance).toList());
        assertInstanceOf(KeyStore.class, received.get(1));
        assertInstanceOf(AcceptAllVerifier.class, received.getLast());
    }

    @Test
    void nothing_configured_leaves_the_builder_untouched() {
        List<Object> received = new ArrayList<>();

        CyranoSslConfigResolver.applySslConfig(FQN, "", lookup(Map.of(FQN + "/mp-rest/trustStore", " ")),
                recordingBuilder(received));

        assertTrue(received.isEmpty(), received.toString());
    }

    @Test
    void default_store_type_is_jks() {
        String location = certsDir.resolve("missing.jks").toUri().toString();
        var lookup = lookup(Map.of(FQN + "/mp-rest/trustStore", location));
        var builder = recordingBuilder(new ArrayList<>());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CyranoSslConfigResolver.applySslConfig(FQN, null, lookup, builder));
        assertTrue(failure.getMessage().contains("type JKS"), failure.getMessage());
    }

    @Test
    void hostname_verifier_that_cannot_be_used_is_reported() {
        var builder = recordingBuilder(new ArrayList<>());
        var missing = lookup(Map.of(FQN + "/mp-rest/hostnameVerifier", "com.example.MissingVerifier"));
        var notAVerifier = lookup(Map.of(FQN + "/mp-rest/hostnameVerifier", String.class.getName()));

        IllegalStateException notFound = assertThrows(IllegalStateException.class,
                () -> CyranoSslConfigResolver.applySslConfig(FQN, null, missing, builder));
        assertInstanceOf(ClassNotFoundException.class, notFound.getCause());
        IllegalStateException wrongType = assertThrows(IllegalStateException.class,
                () -> CyranoSslConfigResolver.applySslConfig(FQN, null, notAVerifier, builder));
        assertInstanceOf(ClassCastException.class, wrongType.getCause());
    }
}
