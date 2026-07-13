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
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

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
        // SSL options (spec §5.6): trustStore / keyStore / sslContext /
        // hostnameVerifier — see CyranoSslSupport for the verifier handling.
        var sslSetup = CyranoSslSupport.build(configuration);
        if (sslSetup != null) {
            builder.sslContext(sslSetup.context());
            if (sslSetup.parameters() != null) {
                builder.sslParameters(sslSetup.parameters());
            }
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

    /**
     * Streaming send for Server-Sent Events (MP Rest Client 4.0 §10) — the
     * returned future completes as soon as the response headers arrive (so the
     * status can be checked before consuming), and the body is exposed as a
     * {@link Flow.Publisher} of byte buffers that the SSE layer subscribes to.
     * Runs on the client's virtual-thread executor like every other send.
     */
    public CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> sendForSse(HttpRequest req) {
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofPublisher());
    }

    @Override
    public void close() {
        // HttpClient has no explicit close before Java 21; in Java 25, we can call
        // client.close() which shuts down the virtual-thread executor.
        client.close();
    }
}
