/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cyrano.cdi.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link CyranoBaseUriResolver}.
 *
 * <p>Spec MicroProfile Rest Client 4.0 §5 "Configuration" — order of priority:
 * {@code <fqn>/mp-rest/url} > {@code <configKey>/mp-rest/url} > {@code @RegisterRestClient(baseUri)}.</p>
 */
class CyranoBaseUriResolverTest {

    private static final String FQN = "com.example.UserApi";

    private static Function<String, Optional<String>> staticLookup(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key));
    }

    @Test
    @DisplayName("§5 priority 3: @RegisterRestClient(baseUri=...) — without MP Config")
    void resolves_baseUri_from_annotation_when_no_mp_config() {
        URI uri = CyranoBaseUriResolver.resolve(
                FQN, "https://api.example.com", "", staticLookup(Map.of()));
        assertEquals(URI.create("https://api.example.com"), uri);
    }

    @Test
    @DisplayName("§5 priority 2: <configKey>/mp-rest/url overrides @RegisterRestClient(baseUri)")
    void mp_config_configKey_overrides_annotation_baseUri() {
        URI uri = CyranoBaseUriResolver.resolve(
                FQN, "https://annotation.example", "users",
                staticLookup(Map.of("users/mp-rest/url", "https://mp-config.example")));
        assertEquals(URI.create("https://mp-config.example"), uri);
    }

    @Test
    @DisplayName("§5 priority 1: <fqn>/mp-rest/url overrides everything else")
    void mp_config_fqn_overrides_configKey_and_annotation() {
        URI uri = CyranoBaseUriResolver.resolve(
                FQN, "https://annotation.example", "users",
                staticLookup(Map.of(
                        "users/mp-rest/url", "https://configkey.example",
                        FQN + "/mp-rest/url", "https://fqn.example")));
        assertEquals(URI.create("https://fqn.example"), uri);
    }

    @Test
    @DisplayName("§5: blank MP Config value is ignored, fallback to annotation")
    void blank_mp_config_value_is_ignored() {
        URI uri = CyranoBaseUriResolver.resolve(
                FQN, "https://annotation.example", "users",
                staticLookup(Map.of(FQN + "/mp-rest/url", "   ")));
        assertEquals(URI.create("https://annotation.example"), uri);
    }

    @Test
    @DisplayName("§5: no source available -> IllegalStateException")
    void throws_when_no_baseUri_source_available() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> CyranoBaseUriResolver.resolve(FQN, "", "", staticLookup(Map.of())));
        assertTrue(ex.getMessage().contains(FQN));
        assertTrue(ex.getMessage().contains("§5"));
    }

    @Test
    @DisplayName("empty configKey -> configKey lookup is skipped")
    void empty_configKey_skips_configKey_lookup() {
        URI uri = CyranoBaseUriResolver.resolve(
                FQN, "https://annotation.example", "",
                staticLookup(Map.of("/mp-rest/url", "https://wrong.example")));
        assertEquals(URI.create("https://annotation.example"), uri);
    }

    @Test
    @DisplayName("defaultMpConfigLookup(): no NPE when MP Config is absent")
    void default_lookup_degrades_gracefully_when_mp_config_absent() {
        // In the cyrano-cdi-vauban test environment, microprofile-config-api
        // is not on the classpath -> function must return Optional.empty()
        // with no NPE or ClassNotFoundException.
        Function<String, Optional<String>> lookup = CyranoBaseUriResolver.defaultMpConfigLookup();
        assertNotNull(lookup);
        assertEquals(Optional.empty(), lookup.apply("anything/mp-rest/url"));
    }
}

