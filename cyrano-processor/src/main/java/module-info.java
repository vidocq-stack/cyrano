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
 * APT annotation processor generating {@code <Interface>$$CyranoClient} sources at
 * compile time for every {@code @RegisterRestClient} interface (and the sub-resource
 * interfaces they reach through locators).
 *
 * <p>The generated client is a plain Java source file emitted via
 * {@code Filer.createSourceFile} — no bytecode manipulation (APT-first rule of the
 * Vidocq workspace, codegen audit CG-01). It embeds a literal
 * {@link io.vidocq.cyrano.spi.gen.ClientDescriptor} and a nested
 * {@code Factory implements ClientProxyFactory} resolved at runtime by
 * {@code ClientProxyRegistry} before any runtime generation, making the
 * application AOT-safe (GraalVM native-image, Project Leyden CDS).</p>
 *
 * <p>Any construct the generator cannot emit faithfully is skipped with a compiler
 * NOTE — correctness never depends on the processor, the runtime fallback always
 * preserves exact spec behaviour.</p>
 */
module io.vidocq.cyrano.processor {
    requires java.compiler;
    // Transitively provides io.vidocq.cyrano.mp.rest.client.api and jakarta.ws.rs.
    requires io.vidocq.cyrano.api;
    requires jakarta.ws.rs;

    // No exports: an APT processor is consumed exclusively through the
    // javax.annotation.processing.Processor SPI below (javac ServiceLoader).
    provides javax.annotation.processing.Processor
            with io.vidocq.cyrano.processor.CyranoClientProcessor;
}
