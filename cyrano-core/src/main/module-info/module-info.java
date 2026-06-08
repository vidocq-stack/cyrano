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
 *
 * <p><strong>JPMS note — testCompile workaround</strong>:
 * {@code module-info.java} lives under {@code src/main/module-info/} (not
 * {@code src/main/java/}) so that the Maven Compiler Plugin does not pick up
 * JPMS during {@code testCompile}. {@code maven-clean-plugin} purges
 * {@code module-info.class} before {@code testCompile} (incremental builds).
 * A {@code prepare-package} execution recompiles {@code module-info.java}
 * alone before the JAR is assembled. Tests run on the classpath
 * ({@code useModulePath=false}) — the JPMS wiring is validated by the TCK.</p>
 */
module io.vidocq.cyrano.core {
    requires transitive io.vidocq.cyrano.api;

    //JSON-B for serialization of query/response bodies (spec §4.2)
    requires jakarta.json.bind;
    requires jakarta.json;

    // Transport HTTP via le JDK (java.net.http)
    requires java.net.http;

    //SPI runtime exported — stable entry point for adapters (cyrano-cdi-vauban).
    //The io.vidocq.cyrano.internal package remains voluntarily unexported
    //(JPMS border of the project).
    exports io.vidocq.cyrano.runtime;

    //SPI MicroProfile Rest Client: the solver is also declared via META-INF/services
    //for ClassLoader fallback (RestClientBuilderResolver.instance() uses both).
    provides org.eclipse.microprofile.rest.client.spi.RestClientBuilderResolver
            with io.vidocq.cyrano.runtime.CyranoRestClientBuilderResolver;

    //M4-3 — SPI for instantization of providers (filters, mappers, etc.): allows
    // adapters such as cyrano-cdi-vauban to provide CDI-managed instances.
    uses io.vidocq.cyrano.runtime.ProviderInstantiator;
}

