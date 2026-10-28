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

import com.sun.net.httpserver.HttpServer;
import io.vidocq.cyrano.cdi.internal.CyranoRestClientCdiIntegrationTest.PingApi;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CyranoRestClientInstanceProducer}: an {@code @Inject @RestClient Instance<T>} gives programmatic access to
 * the {@code @RestClient} bean of the client interface {@code T} (MP Rest Client 4.0 §6.2).
 */
class CyranoRestClientInstanceTest {

    /** {@code Instance<PingApi>}, the type of a {@code @RestClient Instance<PingApi>} injection point. */
    private static final Type INSTANCE_OF_PING_API = new TypeLiteral<Instance<PingApi>>() {}.getType();

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", TestPort.SERVER_PORT), 0);
        server.createContext("/ping/", exchange -> {
            byte[] body = ("hello " + exchange.getRequestURI().getPath().substring("/ping/".length()))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static InjectionPoint injectionPointOf(Type type) {
        return (InjectionPoint) Proxy.newProxyInstance(CyranoRestClientInstanceTest.class.getClassLoader(),
                new Class<?>[] {InjectionPoint.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getType")) {
                        return type;
                    }
                    throw new UnsupportedOperationException("InjectionPoint." + method.getName());
                });
    }

    private static VaubanContainer container() {
        return VaubanContainer.builder()
                .addBeanClass(CyranoRestClientCdiExtension.class)
                .addBeanClass(PingApi.class)
                .build();
    }

    @Test
    void produced_instance_resolves_the_rest_client_bean_spec_section6_2() {
        try (var _ = container()) {
            Instance<PingApi> instance = new CyranoRestClientInstanceProducer().produce(
                    injectionPointOf(INSTANCE_OF_PING_API));

            assertTrue(instance.isResolvable());
            assertFalse(instance.isUnsatisfied());
            assertEquals("hello Instance", instance.get().greet("Instance"));
        }
    }

    @Test
    void injection_point_without_a_client_type_argument_is_unsatisfied() {
        var producer = new CyranoRestClientInstanceProducer();
        InjectionPoint raw = injectionPointOf(Instance.class);
        InjectionPoint wildcard = injectionPointOf(new TypeLiteral<Instance<List<String>>>() {}.getType());

        assertThrows(UnsatisfiedResolutionException.class, () -> producer.produce(null));
        assertThrows(UnsatisfiedResolutionException.class, () -> producer.produce(raw));
        assertThrows(UnsatisfiedResolutionException.class, () -> producer.produce(wildcard));
    }
}
