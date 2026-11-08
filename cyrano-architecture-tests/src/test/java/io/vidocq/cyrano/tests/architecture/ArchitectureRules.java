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
package io.vidocq.cyrano.tests.architecture;

import java.util.Map;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Cyrano's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Cyrano's packages; each names the document it
 * comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The packages of each shipped module: {@code io.vidocq.cyrano.spi} (cyrano-api), {@code io.vidocq.cyrano.internal}
 * and {@code io.vidocq.cyrano.runtime} (cyrano-core), {@code io.vidocq.cyrano.cdi} (cyrano-cdi-vauban) and
 * {@code io.vidocq.cyrano.processor} (cyrano-processor).
 */
final class ArchitectureRules {

    private static final String MP_REST_CLIENT = "org.eclipse.microprofile.rest.client..";

    /** Module descriptor of cyrano-api: Jakarta REST, the MicroProfile REST Client API, and the generated-code contract. */
    static final ArchRule API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cyrano.spi.."},
            Packages.of(Jdk.RUNTIME, "jakarta.ws.rs..", MP_REST_CLIENT, "io.vidocq.cyrano.spi.."),
            "module-info.java of cyrano-api; workspace AGENTS.md, zero external dependency");

    /**
     * Module descriptor of cyrano-core: Jakarta REST, JSON-P and JSON-B, the MicroProfile REST Client and Config
     * APIs, Reactive Streams (optional, {@code requires static}, for reactive return types) and cyrano-api; no CDI.
     */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cyrano.internal..", "io.vidocq.cyrano.runtime.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.ws.rs..",
                    "jakarta.json..",
                    MP_REST_CLIENT,
                    "org.eclipse.microprofile.config..",
                    "org.reactivestreams..",
                    "io.vidocq.cyrano.spi..",
                    "io.vidocq.cyrano.internal..",
                    "io.vidocq.cyrano.runtime.."),
            "module-info.java of cyrano-core; workspace AGENTS.md, zero external dependency");

    /**
     * Module descriptor of cyrano-cdi-vauban: the Jakarta CDI, Inject, Annotation, Interceptor and REST APIs, the
     * MicroProfile REST Client and Config APIs, Vauban's API, cyrano-api and cyrano-core (whose {@code runtime}
     * package it reads, and {@code internal} through the descriptor's qualified export).
     */
    static final ArchRule CDI_VAUBAN_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cyrano.cdi.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "jakarta.annotation..",
                    "jakarta.interceptor..",
                    "jakarta.ws.rs..",
                    MP_REST_CLIENT,
                    "org.eclipse.microprofile.config..",
                    "io.vidocq.vauban.api..",
                    "io.vidocq.cyrano.spi..",
                    "io.vidocq.cyrano.runtime..",
                    "io.vidocq.cyrano.cdi.."),
            "module-info.java of cyrano-cdi-vauban; workspace AGENTS.md, zero external dependency");

    /**
     * Module descriptor of cyrano-processor: a build-time tool on {@code java.compiler}, reading Jakarta REST and
     * the MicroProfile REST Client annotations and cyrano-api's generated-code contract, never shipped.
     */
    static final ArchRule PROCESSOR_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cyrano.processor.."},
            Packages.of(
                    Jdk.COMPILER,
                    "jakarta.ws.rs..",
                    MP_REST_CLIENT,
                    "io.vidocq.cyrano.spi..",
                    "io.vidocq.cyrano.processor.."),
            "module-info.java of cyrano-processor; workspace AGENTS.md, zero external dependency");

    /**
     * CLAUDE.md, Architecture, and the module descriptors: cyrano-api is the bottom layer and reads no other
     * Cyrano module; cyrano-core builds on it and is reached by cyrano-cdi-vauban only; nothing builds on the
     * adapter or on the processor.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            "CLAUDE.md, Architecture; the module-info.java files",
            Layer.named("API").in("io.vidocq.cyrano.spi..").accessesNoLayer(),
            Layer.named("Core").in("io.vidocq.cyrano.internal..", "io.vidocq.cyrano.runtime..").accessibleOnlyBy("CDI Vauban"),
            Layer.named("CDI Vauban").in("io.vidocq.cyrano.cdi..").accessibleByNone(),
            Layer.named("Processor").in("io.vidocq.cyrano.processor..").accessibleByNone());

    private static final String FALLBACK_PATH = "internals.adoc, Generated code and reflective fallback: the"
            + " run-time path that serves an interface without a generated $$CyranoClient (scanning the interface,"
            + " generating and invoking its proxy)";

    private static final String TYPE_CURRENCY = "carries java.lang.reflect.Type, the currency of the JAX-RS and"
            + " MicroProfile REST Client APIs (GenericType, interceptor contexts, message body readers and"
            + " writers); it reads no member";

    private static final String BY_NAME = "the MicroProfile REST Client specification names providers, SSL contexts and"
            + " configuration by class name, so the class is loaded and instantiated by name";

    /**
     * internals.adoc, "Generated code and reflective fallback", and CLAUDE.md (Architecture, proxy resolution):
     * the documented reflective code, each with its reason. Nested classes share the entry of their top-level
     * class.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.ofEntries(
            Map.entry("io.vidocq.cyrano.internal.CyranoInterfaceScanner", FALLBACK_PATH),
            Map.entry("io.vidocq.cyrano.internal.CyranoProxyGenerator", FALLBACK_PATH),
            Map.entry("io.vidocq.cyrano.internal.CyranoInvocationHandler", FALLBACK_PATH),
            Map.entry("io.vidocq.cyrano.internal.RequestSpec", FALLBACK_PATH),
            Map.entry("io.vidocq.cyrano.internal.ParamBinding", FALLBACK_PATH),
            Map.entry(
                    "io.vidocq.cyrano.internal.gen.ClientProxyRegistry",
                    "internals.adoc: after the ServiceLoader, loads <interface>$$CyranoClient by name"),
            Map.entry(
                    "io.vidocq.cyrano.internal.gen.DescriptorConverter",
                    "internals.adoc: converts the literal descriptors of a generated client into the structures the"
                            + " engine reads, resolving the named fields and methods"),
            Map.entry("io.vidocq.cyrano.internal.CyranoRestClientBuilder", BY_NAME),
            Map.entry("io.vidocq.cyrano.internal.PriorityAnnotation", "reads @Priority by qualified name, so"
                    + " cyrano-core needs no compile-time dependency on jakarta.annotation"),
            Map.entry("io.vidocq.cyrano.runtime.ProviderInstantiator", BY_NAME),
            Map.entry("io.vidocq.cyrano.cdi.internal.CyranoBaseUriResolver",
                    "reads the MicroProfile Config of the application by name, so the adapter runs without it"),
            Map.entry("io.vidocq.cyrano.cdi.internal.CyranoCdiProviderInstantiator", BY_NAME),
            Map.entry(
                    "io.vidocq.cyrano.cdi.internal.CyranoRestClientCdiExtension",
                    "internals.adoc: the extension that runs at boot when the processor did not run: it loads the"
                            + " client interfaces by name"),
            Map.entry(
                    "io.vidocq.cyrano.cdi.internal.CyranoRestClientSyntheticCreator",
                    "loads the client interface and its configured providers by name when the bean is created"),
            Map.entry("io.vidocq.cyrano.cdi.internal.CyranoSslConfigResolver", BY_NAME),
            Map.entry("io.vidocq.cyrano.cdi.internal.CyranoRestClientInstanceProducer", TYPE_CURRENCY),
            Map.entry("io.vidocq.cyrano.cdi.internal.CyranoRestClientInstanceSyntheticCreator", TYPE_CURRENCY),
            Map.entry("io.vidocq.cyrano.internal.CyranoClientRequestContext", TYPE_CURRENCY),
            Map.entry("io.vidocq.cyrano.internal.CyranoReaderInterceptorContext", TYPE_CURRENCY),
            Map.entry("io.vidocq.cyrano.internal.CyranoWriterInterceptorContext", TYPE_CURRENCY),
            Map.entry("io.vidocq.cyrano.internal.GenericTypes", TYPE_CURRENCY),
            Map.entry("io.vidocq.cyrano.internal.sse.SseDataDeserializer", TYPE_CURRENCY),
            Map.entry("io.vidocq.cyrano.internal.sse.SseReturnSupport", TYPE_CURRENCY));

    /**
     * Workspace AGENTS.md, "Maximum static code generation", and internals.adoc: reflection remains only as the
     * documented fallbacks above.
     */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "workspace AGENTS.md, static code generation; internals.adoc, reflective fallback");

    /**
     * Workspace AGENTS.md, "Virtual Threads everywhere for I/O": the HTTP transport, the SSE publisher and the
     * asynchronous callbacks run on virtual threads.
     */
    static final ArchRule NO_PLATFORM_THREADS =
            VidocqArchRules.noPlatformThreads(PlatformThreads.NONE, "workspace AGENTS.md, Virtual Threads");

    /** The sources of cyrano-core say it: "no {@code synchronized}, no {@code ThreadLocal} — virtual-thread-friendly". */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "a ThreadLocal neither follows a call across virtual threads nor caches anything",
            "CyranoHttpTransport and SseEventPublisher design notes; workspace AGENTS.md");

    private ArchitectureRules() {
        // Rules only.
    }
}
