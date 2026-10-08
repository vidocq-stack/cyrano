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
/**
 * Standalone MicroProfile Rest Client 4.0 implementation — usable in pure SE,
 * without CDI or any container.
 *
 * <p>Planned components (see ROADMAP.md M1):</p>
 * <ul>
 * <li>{@code CyranoRestClientBuilder} — implementation of the spec's {@code RestClientBuilder}.</li>
 * <li>{@code CyranoInterfaceScanner} — scans JAX-RS annotations on the interface.</li>
 * <li>{@code CyranoProxyGenerator} — generates a proxy named {@code Cyrano$&lt;Interface&gt;}
 *       via the Class-File API (JEP 484), not via {@code java.lang.reflect.Proxy}.</li>
 * <li>{@code CyranoInvocationHandler} — resolves parameters, builds the {@code HttpRequest}.</li>
 * <li>{@code CyranoHttpTransport} — transport via {@code java.net.http.HttpClient}
 *       with {@code VirtualThreadPerTaskExecutor}.</li>
 * </ul>
 *
 * <p>JSON serialization is performed via Jakarta JSON-B (jakarta.json.bind);
 * champollion is the reference implementation provided at runtime.</p>
 */
module io.vidocq.cyrano.core {
    requires transitive io.vidocq.cyrano.api;

    //JSON-B for serialization of query/response bodies (spec §4.2)
    requires jakarta.json.bind;
    requires jakarta.json;

    // Transport HTTP via le JDK (java.net.http)
    requires java.net.http;

    //SSE (MP Rest Client 4.0 §10) — org.reactivestreams.Publisher return types.
    //`static`: only resolved when a client module actually declares Publisher
    //return types (that module requires org.reactivestreams itself); the
    //invocation handler matches the type by name and never loads the SSE
    //bridge otherwise. Interface-only de-facto spec jar (Automatic-Module-Name).
    requires static org.reactivestreams;

    //SPI runtime exported — stable entry point for adapters (cyrano-cdi-vauban).
    //The io.vidocq.cyrano.internal package remains voluntarily unexported
    //(Java Modules border of the project).
    exports io.vidocq.cyrano.runtime;

    //SPI MicroProfile Rest Client: the solver is also declared via META-INF/services
    //for ClassLoader fallback (RestClientBuilderResolver.instance() uses both).
    provides org.eclipse.microprofile.rest.client.spi.RestClientBuilderResolver
            with io.vidocq.cyrano.runtime.CyranoRestClientBuilderResolver;

    //M4-3 — SPI for instantization of providers (filters, mappers, etc.): allows
    // adapters such as cyrano-cdi-vauban to provide CDI-managed instances.
    uses io.vidocq.cyrano.runtime.ProviderInstantiator;

    //CG-01 — generated $$CyranoClient factories (Cyrano annotation processor):
    //a strict Java Modules user module declares `provides ClientProxyFactory with ...`
    //and keeps its client package fully encapsulated.
    uses io.vidocq.cyrano.spi.gen.ClientProxyFactory;

    //MP Rest Client 4.0 §10.1 and §10.2 — the builder and client listeners, looked up with
    //ServiceLoader when a builder is created and when a client is built. Usually provided by
    //another module (humboldt-rest provides a RestClientListener).
    uses org.eclipse.microprofile.rest.client.spi.RestClientBuilderListener;
    uses org.eclipse.microprofile.rest.client.spi.RestClientListener;
}

