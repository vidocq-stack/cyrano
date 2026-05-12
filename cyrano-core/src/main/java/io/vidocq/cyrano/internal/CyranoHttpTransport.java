/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cyrano.internal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Transport HTTP de Cyrano — utilise {@link HttpClient} du JDK avec un
 * {@code VirtualThreadPerTaskExecutor}. Zéro dépendance réseau externe.
 *
 * <p>Spec MicroProfile Rest Client 4.0 §4 (invocation) — l'implémentation choisit
 * librement son transport, ici {@code java.net.http}.</p>
 *
 * <p>Thread-safe : {@link HttpClient} est documenté comme thread-safe. Aucun
 * {@code synchronized}, aucun {@code ThreadLocal} — virtual-thread-friendly.</p>
 */
public final class CyranoHttpTransport implements AutoCloseable {

    private final HttpClient client;

    public CyranoHttpTransport() {
        this(new CyranoClientConfiguration());
    }

    public CyranoHttpTransport(CyranoClientConfiguration configuration) {
        var executor = configuration.getExecutorService() != null
                ? configuration.getExecutorService()
                : Executors.newVirtualThreadPerTaskExecutor();
        HttpClient.Builder builder = HttpClient.newBuilder()
                .executor(executor)
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(configuration.isFollowRedirects()
                        ? HttpClient.Redirect.NORMAL
                        : HttpClient.Redirect.NEVER);
        if (configuration.getConnectTimeoutMs() > 0) {
            builder.connectTimeout(Duration.ofMillis(configuration.getConnectTimeoutMs()));
        }
        if (configuration.getProxyHost() != null) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(
                    configuration.getProxyHost(), configuration.getProxyPort())));
        }
        this.client = builder.build();
    }

    /** Constructeur de test — permet d'injecter un client préconfiguré. */
    CyranoHttpTransport(HttpClient client) {
        this.client = client;
    }

    /** Envoi synchrone — bloque le virtual thread courant. */
    public HttpResponse<String> send(HttpRequest req) throws IOException, InterruptedException {
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Envoi asynchrone — pour les retours {@link java.util.concurrent.CompletionStage}. */
    public CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest req) {
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
        // HttpClient n'a pas de fermeture explicite avant Java 21 ; en Java 25, on peut appeler
        // client.close() qui shutdown l'executor virtual-thread.
        client.close();
    }
}

