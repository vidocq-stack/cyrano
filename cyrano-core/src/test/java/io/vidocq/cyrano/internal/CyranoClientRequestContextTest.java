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

import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CyranoClientRequestContext}: the {@code ClientRequestContext} seen by the
 * {@code ClientRequestFilter}s of a client (MP Rest Client 4.0 §4.2, Jakarta REST §6.3).
 */
class CyranoClientRequestContextTest {

    private static final String ACCEPT_LANGUAGE = "Accept-Language";

    private final CyranoClientConfiguration configuration = new CyranoClientConfiguration();

    @BeforeAll
    static void installRuntimeDelegate() {
        TestRuntimeDelegate.install();
    }
    private final CyranoClientRequestContext ctx =
            new CyranoClientRequestContext(URI.create("http://localhost/items"), "GET", configuration);

    @Test
    void uri_method_and_configuration_are_exposed_and_mutable() {
        assertEquals(URI.create("http://localhost/items"), ctx.getUri());
        assertEquals("GET", ctx.getMethod());
        assertSame(configuration, ctx.getConfiguration());
        assertNull(ctx.getClient());

        ctx.setUri(URI.create("http://localhost/other"));
        ctx.setMethod("PUT");

        assertEquals(URI.create("http://localhost/other"), ctx.getUri());
        assertEquals("PUT", ctx.getMethod());
    }

    @Test
    void abort_with_records_the_response() {
        assertFalse(ctx.isAborted());
        StubResponse response = StubResponse.ofString(401, "denied");

        ctx.abortWith(response);

        assertTrue(ctx.isAborted());
        assertSame(response, ctx.abortResponse());
    }

    @Test
    void properties_can_be_set_replaced_and_removed() {
        ctx.setProperty("a", 1);
        ctx.setProperty("b", 2);
        assertEquals(1, ctx.getProperty("a"));
        assertEquals(2, ctx.getPropertyNames().size());

        ctx.setProperty("a", null);
        ctx.removeProperty("b");

        assertNull(ctx.getProperty("a"));
        assertTrue(ctx.getPropertyNames().isEmpty());
    }

    @Test
    void header_string_joins_multiple_values_and_null_ones() {
        // MultivaluedMap#add ignores null values: a filter can still put a list holding one.
        ctx.getHeaders().put("X-Multi", new ArrayList<>(Arrays.asList("a", null, 3)));

        assertEquals("a,,3", ctx.getHeaderString("X-Multi"));
        assertEquals(List.of("a", "", "3"), ctx.getStringHeaders().get("X-Multi"));
        assertNull(ctx.getHeaderString("X-Absent"));
    }

    @Test
    void contains_header_string_splits_with_the_separator_and_trims() {
        ctx.getHeaders().add("X-List", "one, two ,three");

        assertTrue(ctx.containsHeaderString("X-List", ",", "two"::equals));
        assertFalse(ctx.containsHeaderString("X-List", ",", "four"::equals));
        assertTrue(ctx.containsHeaderString("X-List", null, "one, two ,three"::equals));
        assertFalse(ctx.containsHeaderString("X-Absent", ",", v -> true));
    }

    @Test
    void date_header_is_parsed_and_an_invalid_date_is_ignored() {
        assertNull(ctx.getDate());

        ctx.getHeaders().putSingle("Date", "Tue, 15 Nov 1994 08:12:31 GMT");
        Calendar expected = Calendar.getInstance(TimeZone.getTimeZone("GMT"), Locale.US);
        expected.clear();
        expected.set(1994, Calendar.NOVEMBER, 15, 8, 12, 31);
        assertEquals(expected.getTime(), ctx.getDate());

        ctx.getHeaders().putSingle("Date", "not a date");
        assertNull(ctx.getDate());
    }

    @Test
    void language_headers_are_parsed_as_locales() {
        assertNull(ctx.getLanguage());
        assertTrue(ctx.getAcceptableLanguages().isEmpty());

        ctx.getHeaders().putSingle("Content-Language", "fr-FR");
        ctx.getHeaders().putSingle(ACCEPT_LANGUAGE, "en-US, de");

        assertEquals(Locale.forLanguageTag("fr-FR"), ctx.getLanguage());
        assertEquals(List.of(Locale.forLanguageTag("en-US"), Locale.GERMAN), ctx.getAcceptableLanguages());

        ctx.getHeaders().putSingle(ACCEPT_LANGUAGE, "");
        assertTrue(ctx.getAcceptableLanguages().isEmpty());
    }

    @Test
    void acceptable_media_types_default_to_wildcard() {
        assertEquals(List.of(MediaType.WILDCARD_TYPE), ctx.getAcceptableMediaTypes());
        ctx.getHeaders().putSingle("Accept", "");
        assertEquals(List.of(MediaType.WILDCARD_TYPE), ctx.getAcceptableMediaTypes());
        assertNull(ctx.getMediaType());

        ctx.getHeaders().putSingle("Accept", "text/plain, application/json");
        ctx.getHeaders().putSingle("Content-Type", "application/xml");

        assertEquals(List.of(MediaType.TEXT_PLAIN_TYPE, MediaType.APPLICATION_JSON_TYPE), ctx.getAcceptableMediaTypes());
        assertEquals(MediaType.APPLICATION_XML_TYPE, ctx.getMediaType());
    }

    @Test
    void cookies_are_parsed_from_the_cookie_header_skipping_malformed_pairs() {
        assertTrue(ctx.getCookies().isEmpty());

        ctx.getHeaders().putSingle("Cookie", "session=abc; =nokey; flag; lang = fr");

        Map<String, Cookie> cookies = ctx.getCookies();
        assertEquals(List.of("session", "lang"), List.copyOf(cookies.keySet()));
        assertEquals("abc", cookies.get("session").getValue());
        assertEquals("fr", cookies.get("lang").getValue());

        ctx.getHeaders().putSingle("Cookie", "");
        assertTrue(ctx.getCookies().isEmpty());
    }

    @Test
    void set_entity_records_its_class_and_type() {
        assertFalse(ctx.hasEntity());

        ctx.setEntity("payload");

        assertTrue(ctx.hasEntity());
        assertEquals("payload", ctx.getEntity());
        assertEquals(String.class, ctx.getEntityClass());
        assertEquals(String.class, ctx.getEntityType());

        ctx.setEntity(null);
        assertFalse(ctx.hasEntity());
        assertNull(ctx.getEntityClass());
    }

    @Test
    void set_entity_with_media_type_sets_the_content_type_header() {
        Annotation[] annotations = Test.class.getAnnotations();

        ctx.setEntity(42, annotations, MediaType.APPLICATION_JSON_TYPE);

        assertEquals(Integer.class, ctx.getEntityClass());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, ctx.getMediaType());
        assertEquals(MediaType.APPLICATION_JSON_TYPE.toString(), ctx.getHeaderString("Content-Type"));
        assertArrayEquals(annotations, ctx.getEntityAnnotations());
    }

    @Test
    void set_entity_without_media_type_or_annotations_keeps_headers_untouched() {
        ctx.setEntity(null, null, null);

        assertNull(ctx.getEntityClass());
        assertEquals(0, ctx.getEntityAnnotations().length);
        assertNull(ctx.getHeaderString("Content-Type"));
    }

    @Test
    void internal_entity_setter_keeps_the_declared_generic_type() {
        ctx.setEntityInternal(List.of("a"), List.class, Date.class);

        assertEquals(List.class, ctx.getEntityClass());
        assertEquals(Date.class, ctx.getEntityType());
    }

    @Test
    void entity_stream_can_be_replaced() {
        ByteArrayOutputStream replacement = new ByteArrayOutputStream();

        ctx.setEntityStream(replacement);

        assertSame(replacement, ctx.getEntityStream());
    }
}
