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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * {@link CyranoInvocationHandler} — asynchronous methods (MP Rest Client 4.0, "Asynchronous Support"): the
 * response of a method returning {@link CompletionStage} is processed (response filters, readers) off the thread
 * that called the method, even when the response is already there when the method returns. TCK
 * {@code AsyncMethodTest.testInterfaceMethodWithCompletionStageObjectReturnIsInvokedAsynchronously} checks it with a
 * real server, which makes the failure depend on timing; these tests make the response complete before the
 * handler attaches its continuation.
 */
class CyranoInvocationHandlerTest {

    @Path("/async")
    public interface AsyncApi {
        @GET
        CompletionStage<String> get();
    }

    /** BUG-20261008-05: the response arrived before the method returned. */
    @Test
    void asyncMethod_processesAnAlreadyCompleteResponseOffTheCallerThread() throws Exception {
        var filterThread = new AtomicReference<Thread>();
        var configuration = new CyranoClientConfiguration();
        configuration.registerProvider((ClientResponseFilter) (req, resp) -> filterThread.set(Thread.currentThread()),
                Map.of(ClientResponseFilter.class, 5000));

        String body = call(configuration);

        assertEquals("done", body);
        assertNotNull(filterThread.get(), "the response filter ran");
        assertNotSame(Thread.currentThread(), filterThread.get(), "the response filter ran on the caller's thread");
    }

    /** BUG-20261008-05: a request filter aborted the request; the method still answers asynchronously. */
    @Test
    void asyncMethod_processesAnAbortedRequestOffTheCallerThread() throws Exception {
        var filterThread = new AtomicReference<Thread>();
        var configuration = new CyranoClientConfiguration();
        configuration.registerProvider((ClientRequestFilter) req -> req.abortWith(StubResponse.ofString(200, "aborted")),
                Map.of(ClientRequestFilter.class, 5000));
        configuration.registerProvider((ClientResponseFilter) (req, resp) -> filterThread.set(Thread.currentThread()),
                Map.of(ClientResponseFilter.class, 5000));

        String body = call(configuration);

        assertEquals("aborted", body);
        assertNotNull(filterThread.get(), "the response filter ran");
        assertNotSame(Thread.currentThread(), filterThread.get(), "the response filter ran on the caller's thread");
    }

    private static String call(CyranoClientConfiguration configuration) throws Exception {
        var specs = List.copyOf(CyranoInterfaceScanner.scan(AsyncApi.class).values());
        var handler = new CyranoInvocationHandler(URI.create("http://localhost:1"), specs,
                new AlreadyAnsweredTransport("done"), configuration);
        @SuppressWarnings("unchecked")
        var stage = (CompletionStage<String>) handler.invoke(null, 0, new Object[0]);
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** A transport whose asynchronous send has already completed when it returns. */
    private static final class AlreadyAnsweredTransport extends CyranoHttpTransport {
        private final String body;

        AlreadyAnsweredTransport(String body) {
            this.body = body;
        }

        @Override
        public CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest req) {
            return CompletableFuture.completedFuture(new StringResponse(req, body));
        }
    }

    private record StringResponse(HttpRequest request, String body) implements HttpResponse<String> {
        @Override public int statusCode() { return 200; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("Content-Type", List.of("text/plain")), (k, v) -> true);
        }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
