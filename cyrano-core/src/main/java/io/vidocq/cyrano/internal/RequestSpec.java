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
 * Description immuable d'une requête HTTP dérivée d'une méthode d'interface client.
 *
 * <p>Construit une seule fois par {@link CyranoInterfaceScanner} et partagé entre toutes
 * les invocations de la méthode (thread-safe car immuable).</p>
 *
 * @param httpMethod      verbe HTTP en majuscules
 * @param pathTemplate    template de path, peut contenir des variables {@code {name}}
 * @param bindings        liaisons des paramètres Java vers les emplacements HTTP
 * @param returnType      type de retour Java (raw)
 * @param genericReturnType type générique complet pour la désérialisation JSON-B
 * @param consumes        valeurs {@code @Consumes} (Content-Type d'envoi)
 * @param produces        valeurs {@code @Produces} (Accept en réception)
 * @param staticHeaders   headers fixes via {@code @ClientHeaderParam(value="literal")} (spec §6.5)
 * @param dynamicHeaders  headers calculés via {@code @ClientHeaderParam(value="{methodName}")} —
 *                        chaque valeur capture le nom de méthode et le flag {@code required}
 * @param method          référence vers la méthode source (utilisée par les headers dynamiques)
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
     * En-tête dynamique {@code @ClientHeaderParam(value="{methodName}")} — capture
     * le nom de la méthode {@code default}/{@code static} qui calcule la valeur
     * ainsi que le flag {@code required} (spec §6.5).
     *
     * @param methodName nom de la méthode {@code default}/{@code static} d'invocation
     * @param required   {@code true} (défaut) — toute exception est propagée ;
     *                   {@code false} — l'en-tête est silencieusement omis si l'invocation échoue
     */
    public record DynamicHeader(String methodName, boolean required) {
    }
}
