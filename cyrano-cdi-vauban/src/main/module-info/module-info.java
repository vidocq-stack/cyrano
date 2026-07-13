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
 * Optional integration of Cyrano with the Vauban container — automatic discovery of
 * interfaces annotated {@code @RegisterRestClient} via a Build Compatible Extension,
 * production of the corresponding CDI beans qualified with {@code @RestClient}.
 *
 * <p>Optional module: a standalone SE deployment does not need this module
 * and can build its proxies via {@code RestClientBuilder.newBuilder()}.</p>
 *
 * <p>Base URI configuration relies on {@code @RegisterRestClient(baseUri=...)}
 * (low priority) and MicroProfile Config (Ravel, high priority,
 * {@code &lt;fqn&gt;/mp-rest/url}).</p>
 *
 * <p><strong>Java Modules note — testCompile workaround</strong>:
 * {@code module-info.java} lives under {@code src/main/module-info/} so the Maven
 * Compiler Plugin does not pick up Java Modules during {@code testCompile} (vauban-core is
 * test-scope, absent from {@code target/javamodules/}).</p>
 *
 * <p>For strict Java Modules deployment in production, plan to add
 * {@code opens io.vidocq.cyrano.cdi.internal to io.vidocq.vauban.core} so that
 * Vauban can introspect the internal CDI beans (to be activated in M3).</p>
 */
module io.vidocq.cyrano.cdi.vauban {
    requires transitive io.vidocq.cyrano.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;
    // Compile-only (optional at runtime): supplies the VaubanComponentProvider service type.
    requires static io.vidocq.vauban.api;

    exports io.vidocq.cyrano.cdi.internal;

    //ECB Cyrano — exposes Vauban via ServiceLoader + Java Modules providers (spec MP Rest Client 4.0 6).
    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.cyrano.cdi.internal.CyranoRestClientCdiExtension;

    //M4-3 — ProviderInstantiator CDI-aware (outs @RegisterProvider via BeanManager).
    provides io.vidocq.cyrano.runtime.ProviderInstantiator
            with io.vidocq.cyrano.cdi.internal.CyranoCdiProviderInstantiator;

    // In-module instantiation and producer invocation of this package's beans (the @Produces in
    // CyranoRestClientInstanceProducer and the synthetic creators/disposers), generated as
    // _VaubanComponents co-located in io.vidocq.cyrano.cdi.internal — so the container needs no
    // `opens … to io.vidocq.vauban.core`. APT-generated, inert under Weld.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.cyrano.cdi.internal._VaubanComponents;
}

