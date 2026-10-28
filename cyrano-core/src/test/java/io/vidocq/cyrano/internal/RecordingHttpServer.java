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

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JDK {@link HttpServer} on a random loopback port that records the last request and answers
 * with a configurable status, body and content type. {@code echoQuery} mode answers with the
 * raw value of the {@code v} query parameter instead.
 */
final class RecordingHttpServer implements AutoCloseable {

    /** The last request the server received. */
    record Exchange(String method, String rawPath, String query, Headers headers, String body) {
        String header(String name) {
            return headers.getFirst(name);
        }
    }

    private final HttpServer server;
    private final AtomicReference<Exchange> last = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "ok";
    private volatile String contentType;
    private volatile boolean echoQuery;

    RecordingHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            URI uri = ex.getRequestURI();
            last.set(new Exchange(ex.getRequestMethod(), uri.getRawPath(), uri.getQuery(),
                    new Headers(ex.getRequestHeaders()), body));
            String answer = echoQuery ? queryValue(uri.getQuery()) : responseBody;
            if (contentType != null) {
                ex.getResponseHeaders().add("Content-Type", contentType);
            }
            byte[] payload = answer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            try (var os = ex.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
    }

    private static String queryValue(String query) {
        if (query == null) return "";
        int eq = query.indexOf('=');
        return eq < 0 ? "" : query.substring(eq + 1);
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    Exchange last() {
        return last.get();
    }

    RecordingHttpServer respond(int status, String body) {
        this.status = status;
        this.responseBody = body;
        return this;
    }

    RecordingHttpServer contentType(String contentType) {
        this.contentType = contentType;
        return this;
    }

    RecordingHttpServer echoQuery() {
        this.echoQuery = true;
        return this;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
