/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cyrano.internal;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Unchangeable description of an HTTP request derived from a client interface method.
 *
 * <p>Built once by {@link CyranoInterfaceScanner} and shared among all
 * the invocations of the method (thread-safe car immutable).</p>
 *
 * @param httpMethod      verbe HTTP en majuscules
 * @param pathTemplate    path template, may contain {@code {name}} variables
 * @param bindings from Java settings to HTTP locations
 * @param returnType      type de retour Java (raw)
 * @param genericReturnComplete generic type type for JSON-B deserialization
 * @param consumes        valeurs {@code @Consumes} (Content-Type d'envoi)
 * @param produced values {@code @Produces} (Accept on receipt)
 * @param staticHeaders fixed via {@code @ClientHeaderParam(value="literal")} (spec §6.5)
 * @param dynamicHeaders calculated via {@code @ClientHeaderParam(value="{methodName}")} —
 * each value captures the method name and flag {@code required}
 * @param method reference to the source method (used by dynamic headers)
 */
public record RequestSpec(
        String httpMethod,
        String pathTemplate,
        List<ParamBinding> bindings,
        Class<?> returnType,
        Type genericReturnType,
        List<String> consumes,
        List<String> produces,
        Map<String, List<String>> staticHeaders,
        Map<String, DynamicHeader> dynamicHeaders,
        Method method
) {
    public RequestSpec {
        bindings = List.copyOf(bindings);
        consumes = List.copyOf(consumes);
        produces = List.copyOf(produces);
        staticHeaders = Map.copyOf(staticHeaders);
        dynamicHeaders = Map.copyOf(dynamicHeaders);
    }

    /**
     * Dynamic header {@code @ClientHeaderParam(value="{methodName}")} — capture
     * the name of the {@code default}/{@code static} method which calculates the value
     * and flag {@code required} (spec §6.5).
     *
     * @param methodName of the {@code default}/{@code static} method of invocation
     * @param required {@code true} (default) — any exception is spread;
     * {@code false} — the header is silently omitted if the invocation fails
     */
    public record DynamicHeader(String methodName, boolean required) {
    }
}
