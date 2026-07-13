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

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec MP Rest Client 4.0 §5.6 (SSL configuration) — the builder's
 * {@code trustStore}, {@code keyStore}, {@code sslContext} and
 * {@code hostnameVerifier} must actually configure the HTTPS transport
 * (TCK {@code ssl/**} suites). Server side: the JDK's
 * {@code com.sun.net.httpserver.HttpsServer}; certificates generated per
 * run with {@code keytool} — no committed binary stores.
 */
class CyranoSslTransportTest {

    static final String BODY = "{\"foo\":\"bar\"}";
    static final char[] PASSWORD = "password".toCharArray();

    static java.nio.file.Path certsDir;
    static KeyStore serverKeyStore;      // CN=localhost + SAN
    static KeyStore clientTrustStore;    // trusts server cert
    static KeyStore wrongHostKeyStore;   // CN=wrong.example.com (no SAN)
    static KeyStore wrongHostTrustStore; // trusts wrong-host cert
    static KeyStore clientKeyStore;      // client identity (mutual TLS)
    static KeyStore serverTrustStore;    // trusts client cert

    static HttpsServer simpleServer;     // server cert, no client auth
    static HttpsServer mutualServer;     // server cert, needClientAuth
    static HttpsServer wrongHostServer;  // wrong-hostname cert

    @Path("/")
    public interface SecureApi {
        @GET
        @Path("/simple")
        String get();
    }

    @BeforeAll
    static void setUp() throws Exception {
        certsDir = Files.createTempDirectory("cyrano-ssl-test-");
        genKeyPair("server", "CN=localhost", "SAN=dns:localhost,ip:127.0.0.1");
        genKeyPair("wronghost", "CN=wrong.example.com", null);
        genKeyPair("client", "CN=cyrano-test-client", null);
        exportAndImport("server", "clienttrust");
        exportAndImport("wronghost", "wronghosttrust");
        exportAndImport("client", "servertrust");

        serverKeyStore = load("server");
        clientTrustStore = load("clienttrust");
        wrongHostKeyStore = load("wronghost");
        wrongHostTrustStore = load("wronghosttrust");
        clientKeyStore = load("client");
        serverTrustStore = load("servertrust");

        simpleServer = startServer(serverKeyStore, null, false);
        mutualServer = startServer(serverKeyStore, serverTrustStore, true);
        wrongHostServer = startServer(wrongHostKeyStore, null, false);
    }

    @AfterAll
    static void tearDown() {
        for (HttpsServer s : new HttpsServer[] {simpleServer, mutualServer, wrongHostServer}) {
            if (s != null) s.stop(0);
        }
    }

    private static URI uri(HttpsServer server) {
        return URI.create("https://localhost:" + server.getAddress().getPort() + "/");
    }

    // ------------------------------------------------------------------
    // trustStore (TCK SslTrustStoreTest)
    // ------------------------------------------------------------------

    @Test
    void self_signed_server_fails_without_trust_store_spec_section5_6() {
        SecureApi api = RestClientBuilder.newBuilder()
                .baseUri(uri(simpleServer))
                .build(SecureApi.class);
        assertThrows(ProcessingException.class, api::get,
                "a self-signed server certificate must be rejected without a configured trust store");
    }

    @Test
    void trust_store_enables_the_self_signed_server_spec_section5_6() {
        SecureApi api = RestClientBuilder.newBuilder()
                .baseUri(uri(simpleServer))
                .trustStore(clientTrustStore)
                .build(SecureApi.class);
        assertEquals(BODY, api.get());
    }

    @Test
    void non_matching_trust_store_fails_spec_section5_6() {
        SecureApi api = RestClientBuilder.newBuilder()
                .baseUri(uri(simpleServer))
                .trustStore(wrongHostTrustStore)
                .build(SecureApi.class);
        assertThrows(ProcessingException.class, api::get);
    }

    // ------------------------------------------------------------------
    // keyStore / mutual TLS (TCK SslMutualTest)
    // ------------------------------------------------------------------

    @Test
    void mutual_tls_fails_without_client_key_store_spec_section5_6() {
        SecureApi api = RestClientBuilder.newBuilder()
                .baseUri(uri(mutualServer))
                .trustStore(clientTrustStore)
                .build(SecureApi.class);
        assertThrows(ProcessingException.class, api::get);
    }

    @Test
    void mutual_tls_succeeds_with_client_key_store_spec_section5_6() {
        SecureApi api = RestClientBuilder.newBuilder()
                .baseUri(uri(mutualServer))
                .trustStore(clientTrustStore)
                .keyStore(clientKeyStore, "password")
                .build(SecureApi.class);
        assertEquals(BODY, api.get());
    }

    // ------------------------------------------------------------------
    // hostnameVerifier (TCK SslHostnameVerifierTest)
    // ------------------------------------------------------------------

    @Test
    void wrong_hostname_fails_without_verifier_spec_section5_6() {
        SecureApi api = RestClientBuilder.newBuilder()
                .baseUri(uri(wrongHostServer))
                .trustStore(wrongHostTrustStore)
                .build(SecureApi.class);
        assertThrows(ProcessingException.class, api::get,
                "endpoint identification must reject a certificate whose CN does not match the host");
    }

    @Test
    void accepting_verifier_overrides_hostname_check_spec_section5_6() {
        SecureApi api = RestClientBuilder.newBuilder()
                .baseUri(uri(wrongHostServer))
                .trustStore(wrongHostTrustStore)
                .hostnameVerifier((hostname, session) -> true)
                .build(SecureApi.class);
        assertEquals(BODY, api.get());
    }

    @Test
    void rejecting_verifier_fails_the_handshake_spec_section5_6() {
        SecureApi api = RestClientBuilder.newBuilder()
                .baseUri(uri(wrongHostServer))
                .trustStore(wrongHostTrustStore)
                .hostnameVerifier((hostname, session) -> false)
                .build(SecureApi.class);
        assertThrows(ProcessingException.class, api::get);
    }

    @Test
    void verifier_receives_the_hostname_and_the_ssl_session_spec_section5_6() {
        AtomicReference<String> seenHost = new AtomicReference<>();
        AtomicReference<SSLSession> seenSession = new AtomicReference<>();
        HostnameVerifier verifier = (hostname, session) -> {
            seenHost.set(hostname);
            seenSession.set(session);
            return true;
        };
        SecureApi api = RestClientBuilder.newBuilder()
                .baseUri(uri(wrongHostServer))
                .trustStore(wrongHostTrustStore)
                .hostnameVerifier(verifier)
                .build(SecureApi.class);
        assertEquals(BODY, api.get());
        assertEquals("localhost", seenHost.get());
        assertNotNull(seenSession.get(), "the verifier must receive the handshake SSLSession");
    }

    // ------------------------------------------------------------------
    // explicit SSLContext (TCK SslContextTest)
    // ------------------------------------------------------------------

    @Test
    void explicit_ssl_context_is_used_spec_section5_6() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(clientTrustStore);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);

        SecureApi api = RestClientBuilder.newBuilder()
                .baseUri(uri(simpleServer))
                .sslContext(ctx)
                .build(SecureApi.class);
        assertEquals(BODY, api.get());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static void genKeyPair(String alias, String dname, String sanExt) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                keytool(), "-genkeypair", "-keyalg", "RSA", "-keysize", "2048",
                "-alias", alias, "-dname", dname,
                "-validity", "1", "-storetype", "PKCS12",
                "-keystore", certsDir.resolve(alias + ".p12").toString(),
                "-storepass", "password"));
        if (sanExt != null) {
            cmd.add("-ext");
            cmd.add(sanExt);
        }
        run(cmd);
    }

    private static void exportAndImport(String alias, String trustName) throws Exception {
        java.nio.file.Path cert = certsDir.resolve(alias + ".cer");
        run(List.of(keytool(), "-exportcert", "-alias", alias,
                "-keystore", certsDir.resolve(alias + ".p12").toString(),
                "-storepass", "password", "-file", cert.toString()));
        run(List.of(keytool(), "-importcert", "-noprompt", "-alias", alias,
                "-keystore", certsDir.resolve(trustName + ".p12").toString(),
                "-storetype", "PKCS12", "-storepass", "password",
                "-file", cert.toString()));
    }

    private static String keytool() {
        return java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    }

    private static void run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + String.join(" ", cmd) + "\n" + out);
        }
    }

    private static KeyStore load(String name) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(certsDir.resolve(name + ".p12"))) {
            ks.load(in, PASSWORD);
        }
        return ks;
    }

    private static HttpsServer startServer(KeyStore identity, KeyStore trusted, boolean needClientAuth)
            throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(identity, PASSWORD);
        TrustManagerFactory tmf = null;
        if (trusted != null) {
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trusted);
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf != null ? tmf.getTrustManagers() : null, null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx) {
            @Override
            public void configure(HttpsParameters params) {
                var ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(needClientAuth);
                params.setSSLParameters(ssl);
            }
        });
        server.createContext("/simple", exchange -> {
            byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    @SuppressWarnings("unused")
    private static void quietClose(IOException ignored) {
        // helper placeholder
    }
}
