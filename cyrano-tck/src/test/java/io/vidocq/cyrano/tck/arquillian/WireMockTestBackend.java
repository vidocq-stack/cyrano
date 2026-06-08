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

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Singleton WireMock whose life cycle covers the entire test JVM —
 * started lazily, never stopped (a {@code Runtime.addShutdownHook}
 * handles release).
 *
 * <p>Binding on the Arquillian life cycle proved fragile under the TestNG
 * Suite Runner: {@code start()}/{@code stop()} are only called once at boot,
 * before tests had a chance to run. We bypass this by placing WireMock in a
 * JVM-static state: the instance is started on first request (e.g. from
 * {@code CyranoArquillianExtension.register()}) and stays alive until the end
 * of the process.</p>
 *
 * <p>The default listening port is {@code 8765} — the value hardcoded in the
 * spec's {@code WiremockArquillianTest.setupWireMockConnection()} — overridable
 * via {@code -Dwiremock.server.port=...}.</p>
 */
final class WireMockTestBackend {

    /**
     * WireMock listening port — the default value {@code 8765} is the one
     * hardcoded in {@code WiremockArquillianTest.setupWireMockConnection()}.
     */
    static final int PORT = Integer.getInteger("wiremock.server.port", 8765);

    private static volatile WireMockServer server;

    private WireMockTestBackend() {}

    static String serverDebug() {
        return server == null ? "null" : ("running=" + server.isRunning() + " port=" + server.port());
    }

    /**
     * Quick HTTP probe on WireMock admin — returns {@code true} if the
     * server's responding. Used by {@link WireMockProbeListener} to trace
     * availability before each TestNG method.
     */
    static boolean isAlive() {
        if (server == null) return false;
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            var req = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("http://127.0.0.1:" + PORT + "/__admin/mappings"))
                    .timeout(java.time.Duration.ofMillis(500))
                    .GET()
                    .build();
            var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (java.io.IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Starts the shared WireMock instance (idempotent). Thread-safe via
     * double-checked locking on the {@code server} reference.
     */
    static synchronized void start() {
        if (server != null && server.isRunning()) {
            System.err.println("[CyranoTCK] WireMock already running on port " + server.port()
                    + " (pid=" + ProcessHandle.current().pid() + ")");
            return;
        }
        server = new WireMockServer(WireMockConfiguration.options()
                .bindAddress("127.0.0.1")
                .port(PORT)
                .notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false)));
        server.start();
        System.err.println("[CyranoTCK] WireMock backend ready on port " + server.port()
                + " (pid=" + ProcessHandle.current().pid()
                + " classloader=" + WireMockTestBackend.class.getClassLoader() + ")");
        //Set the WireMock static client to 127.0.0.1 — without this,
        //{@code WireMock.reset()} called by TCK tests would point on
        //{@code localhost:8080/__admin} (utilitarian class failure),
        //what would result in "Connecting refused" from the first
        //{@code @BeforeMethod}. Force target avoids addiction
        //implicit in the DNS resolution of "localhost" (which on macOS can
        //leave on::1 while WireMock only listens on 127.0.0.1).
        com.github.tomakehurst.wiremock.client.WireMock.configureFor("127.0.0.1", server.port());
        //HTTP admin probe — diagnoses any bind/firewall problem.
        probeAdmin("http://127.0.0.1:" + server.port() + "/__admin/mappings");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.stop(); } catch (RuntimeException ignored) {}
        }, "cyrano-tck-wiremock-shutdown"));
    }

    private static void probeAdmin(String url) {
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(2))
                    .GET()
                    .build();
            var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
            System.err.println("[CyranoTCK] WireMock admin probe OK — HTTP " + resp.statusCode()
                    + " on " + url);
        } catch (java.io.IOException | InterruptedException e) {
            System.err.println("[CyranoTCK] WireMock admin probe KO : " + e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }
}




