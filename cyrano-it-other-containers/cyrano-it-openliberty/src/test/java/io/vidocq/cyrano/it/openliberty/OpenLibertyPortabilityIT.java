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
package io.vidocq.cyrano.it.openliberty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Cyrano jars, unchanged, inside a WAR on Open Liberty (vidocq-workspace#15, cyrano#34):
 * Liberty's CDI runs the build compatible extension, Liberty's MicroProfile Config supplies the base
 * URI, and Liberty's JSON-B reads the body. Liberty's mpRestClient feature is off, so every call
 * here goes through Cyrano.
 */
class OpenLibertyPortabilityIT {

    private static final String BASE = System.getProperty("cyrano.it.base");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void injectedRestClientUsesTheProcessorGeneratedProxy() throws Exception {
        String[] answer = get("/call/injected/liberty").split("\\|");
        assertEquals("hello liberty", answer[0]);
        assertEquals("13", answer[1]);
        assertTrue(answer[2].contains("$$CyranoClient"), answer[2]);
    }

    @Test
    void programmaticClientUsesTheRunTimeFallback() throws Exception {
        String[] answer = get("/call/plain/fallback").split("\\|");
        assertEquals("hello fallback", answer[0]);
        assertFalse(answer[2].contains("$$CyranoClient"), answer[2]);
    }

    private static String get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return response.body();
    }
}
