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
 * Cyrano HTTP Transport — uses {@link HttpClient} from the JDK with a
 * {@code VirtualThreadPerTaskExecutor}. Zero external network dependency.
 *
 * <p>Spec MicroProfile Rest Client 4.0 §4 (invocation) — the implementation chooses
 * its transport freely, here {@code java.net.http}.</p>
 *
 * <p>Thread-safe: {@link HttpClient} is documented as thread-safe. No
 * {@code synchronized}, no {@code ThreadLocal} — virtual-thread-friendly.</p>
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

    /** Test constructor — allows injecting a preconfigured client. */
    CyranoHttpTransport(HttpClient client) {
        this.client = client;
    }

    /** Synchronous send — blocks the current virtual thread. */
    public HttpResponse<String> send(HttpRequest req) throws IOException, InterruptedException {
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Asynchronous send — for {@link java.util.concurrent.CompletionStage} return types. */
    public CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest req) {
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
        // HttpClient has no explicit close before Java 21; in Java 25, we can call
        // client.close() which shuts down the virtual-thread executor.
        client.close();
    }
}
