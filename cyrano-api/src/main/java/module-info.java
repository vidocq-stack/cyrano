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
 * Cyrano API: controlled re-exposure of the MicroProfile Rest Client 4.0 spec
 * and stable public SPI. The content will be expanded over the milestones (M1+).
 *
 * <p><strong>JPMS note — jlink compatibility</strong>: the MP Rest Client spec is
 * imported via {@code io.vidocq.cyrano.mp.rest.client.api}, an explicit repackage
 * module that avoids the use of an automatic module in the graph.</p>
 */
module io.vidocq.cyrano.api {
    requires transitive io.vidocq.cyrano.mp.rest.client.api;
    requires transitive jakarta.ws.rs;

    exports io.vidocq.cyrano.spi;
}
