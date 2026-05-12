/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.vidocq.cyrano.tck.arquillian;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Singleton WireMock dont le cycle de vie couvre toute la JVM de test —
 * démarré paresseusement, jamais arrêté (un {@code Runtime.addShutdownHook}
 * gère la libération).
 *
 * <p>Le binding au cycle de vie d'Arquillian s'est révélé fragile dans le mode
 * « suite » du runner TestNG : {@code start()}/{@code stop()} sont appelés
 * une seule fois au boot, avant que les tests n'aient une chance de tourner.
 * On contourne en plaçant WireMock dans un état JVM-static : l'instance est
 * démarrée à la première sollicitation (par exemple dans
 * {@code CyranoArquillianExtension.register()}) et reste vivante jusqu'à
 * la fin du processus.</p>
 *
 * <p>Le port d'écoute est {@code 8765} par défaut — la valeur codée en dur
 * dans {@code WiremockArquillianTest.setupWireMockConnection()} de la spec —
 * surchargeable via {@code -Dwiremock.server.port=...}.</p>
 */
final class WireMockTestBackend {

    /**
     * Port d'écoute WireMock — la valeur par défaut {@code 8765} est celle
     * codée en dur dans {@code WiremockArquillianTest.setupWireMockConnection()}.
     */
    static final int PORT = Integer.getInteger("wiremock.server.port", 8765);

    private static volatile WireMockServer server;

    private WireMockTestBackend() {}

    static String serverDebug() {
        return server == null ? "null" : ("running=" + server.isRunning() + " port=" + server.port());
    }

    /**
     * Sonde HTTP rapide sur l'admin WireMock — retourne {@code true} si le
     * serveur répond. Utilisée par {@link WireMockProbeListener} pour tracer
     * la disponibilité avant chaque méthode TestNG.
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
     * Démarre l'instance WireMock partagée (idempotent). Thread-safe via
     * double-checked locking sur la référence {@code server}.
     */
    static synchronized void start() {
        if (server != null && server.isRunning()) {
            System.err.println("[CyranoTCK] WireMock déjà en place sur port " + server.port()
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
        // Configure le client WireMock statique sur 127.0.0.1 — sans cela,
        // {@code WireMock.reset()} appelé par les tests TCK pointerait sur
        // {@code localhost:8080/__admin} (défaut de la classe utilitaire),
        // ce qui résulterait en « Connection refused » dès le premier
        // {@code @BeforeMethod}. Forcer la cible évite cette dépendance
        // implicite à la résolution DNS de "localhost" (qui sur macOS peut
        // partir sur ::1 alors que WireMock n'écoute que sur 127.0.0.1).
        com.github.tomakehurst.wiremock.client.WireMock.configureFor("127.0.0.1", server.port());
        // Sonde HTTP admin — diagnostique tout problème de bind/firewall.
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




