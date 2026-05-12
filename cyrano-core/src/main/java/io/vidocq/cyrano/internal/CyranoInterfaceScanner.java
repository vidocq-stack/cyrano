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

import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParams;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scanner d'interface client — extrait les {@link RequestSpec} à partir des annotations
 * JAX-RS d'une interface annotée selon la spec MicroProfile Rest Client 4.0 §3.
 *
 * <p>Couverture M2 : verbes HTTP standard, {@link Path}, {@link PathParam},
 * {@link QueryParam}, {@link HeaderParam}, {@link CookieParam}, {@link FormParam},
 * {@link MatrixParam}, {@link BeanParam}, {@link DefaultValue}, {@link Consumes},
 * {@link Produces}, {@link ClientHeaderParam} (statique et dynamique), corps implicite.</p>
 */
public final class CyranoInterfaceScanner {

    private static final Pattern TEMPLATE_PARAM_PATTERN = Pattern.compile("\\{([^}/]+)}");

    private CyranoInterfaceScanner() {
        // utility
    }

    public static Map<Method, RequestSpec> scan(Class<?> iface) {
        if (!iface.isInterface()) {
            throw new IllegalArgumentException("Cyrano ne sait gérer que des interfaces : " + iface);
        }
        String basePath = normalize(extractPath(iface));
        // Headers @ClientHeaderParam déclarés au niveau de l'interface — appliqués à toutes les méthodes
        Map<String, List<String>> typeStatic = new LinkedHashMap<>();
        Map<String, RequestSpec.DynamicHeader> typeDynamic = new LinkedHashMap<>();
        validateClientHeaderParams(iface, iface, iface.getAnnotationsByType(ClientHeaderParam.class));
        collectClientHeaders(iface.getAnnotationsByType(ClientHeaderParam.class), typeStatic, typeDynamic);
        for (ClientHeaderParams cs : iface.getAnnotationsByType(ClientHeaderParams.class)) {
            validateClientHeaderParams(iface, iface, cs.value());
            collectClientHeaders(cs.value(), typeStatic, typeDynamic);
        }
        // Consumes/Produces au niveau du type
        List<String> typeConsumes = extractMediaTypes(iface.getAnnotation(Consumes.class));
        List<String> typeProduces = extractMediaTypes(iface.getAnnotation(Produces.class));

        Map<Method, RequestSpec> specs = new LinkedHashMap<>();
        for (Method m : iface.getMethods()) {
            String httpMethod = extractHttpMethod(m, iface);
            String subPath = normalize(extractPath(m));
            String template = joinPath(basePath, subPath);

            List<ParamBinding> bindings = extractBindings(m);

            Map<String, List<String>> staticHeaders = new LinkedHashMap<>(typeStatic);
            Map<String, RequestSpec.DynamicHeader> dynamicHeaders = new LinkedHashMap<>(typeDynamic);
            validateClientHeaderParams(iface, m, m.getAnnotationsByType(ClientHeaderParam.class));
            collectClientHeaders(m.getAnnotationsByType(ClientHeaderParam.class), staticHeaders, dynamicHeaders);
            for (ClientHeaderParams cs : m.getAnnotationsByType(ClientHeaderParams.class)) {
                validateClientHeaderParams(iface, m, cs.value());
                collectClientHeaders(cs.value(), staticHeaders, dynamicHeaders);
            }

            List<String> consumes = m.isAnnotationPresent(Consumes.class)
                    ? extractMediaTypes(m.getAnnotation(Consumes.class)) : typeConsumes;
            List<String> produces = m.isAnnotationPresent(Produces.class)
                    ? extractMediaTypes(m.getAnnotation(Produces.class)) : typeProduces;

            if (httpMethod == null) {
                // Sub-resource locator: @Path but no HTTP verb, returning an interface.
                // Represented with httpMethod=null — handler dispatches by building a sub-proxy.
                if (m.isAnnotationPresent(Path.class) && m.getReturnType().isInterface()) {
                    specs.put(m, new RequestSpec(
                            null, template, bindings,
                            m.getReturnType(), m.getGenericReturnType(),
                            consumes, produces,
                            staticHeaders, dynamicHeaders,
                            m));
                }
                continue;
            }

            validatePathTemplateBindings(iface, m, template, bindings);

            specs.put(m, new RequestSpec(
                    httpMethod, template, bindings,
                    m.getReturnType(), m.getGenericReturnType(),
                    consumes, produces,
                    staticHeaders, dynamicHeaders,
                    m));
        }
        if (specs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Aucune méthode annotée par un verbe HTTP sur " + iface.getName()
                            + " — spec MicroProfile Rest Client 4.0 §3 exige au moins une.");
        }
        return Map.copyOf(specs);
    }

    private static String extractHttpMethod(Method m, Class<?> iface) {
        String value = null;
        for (Annotation a : m.getAnnotations()) {
            HttpMethod http = a.annotationType().getAnnotation(HttpMethod.class);
            if (http != null) {
                if (value != null) {
                    throw definitionError(iface, m,
                            "Une méthode client ne peut pas déclarer plusieurs annotations HTTP");
                }
                value = http.value();
            }
        }
        return value;
    }

    private static void validatePathTemplateBindings(Class<?> iface, Method method,
                                                     String template,
                                                     List<ParamBinding> bindings) {
        Set<String> placeholders = extractTemplateParams(template);
        Set<String> boundPathParams = new LinkedHashSet<>();
        for (ParamBinding binding : bindings) {
            collectPathBindingNames(binding, boundPathParams);
        }
        for (String bound : boundPathParams) {
            if (!placeholders.contains(bound)) {
                throw definitionError(iface, method,
                        "@PathParam('" + bound + "') n'a pas de placeholder correspondant dans le template '"
                                + template + "'");
            }
        }
        for (String placeholder : placeholders) {
            if (!boundPathParams.contains(placeholder)) {
                throw definitionError(iface, method,
                        "Le placeholder '{" + placeholder + "}' n'a pas de @PathParam correspondant");
            }
        }
    }

    private static Set<String> extractTemplateParams(String template) {
        Set<String> out = new LinkedHashSet<>();
        Matcher matcher = TEMPLATE_PARAM_PATTERN.matcher(template);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }

    private static void collectPathBindingNames(ParamBinding binding, Set<String> out) {
        if (binding instanceof ParamBinding.Path path) {
            out.add(path.name());
            return;
        }
        if (binding instanceof ParamBinding.Bean bean) {
            for (ParamBinding.FieldBinding field : bean.fields()) {
                if (field.kind() == ParamBinding.FieldBinding.Kind.PATH) {
                    out.add(field.name());
                }
            }
        }
    }

    private static void validateClientHeaderParams(Class<?> iface, Object target, ClientHeaderParam[] anns) {
        Set<String> seenHeaders = new HashSet<>();
        for (ClientHeaderParam ann : anns) {
            String headerName = ann.name();
            if (!seenHeaders.add(headerName)) {
                throw definitionError(iface, target,
                        "Le header '" + headerName + "' est déclaré plusieurs fois via @ClientHeaderParam");
            }
            validateClientHeaderValue(iface, target, ann);
        }
    }

    private static void validateClientHeaderValue(Class<?> iface, Object target, ClientHeaderParam ann) {
        String[] values = ann.value();
        boolean hasCompute = false;
        String computeRef = null;
        for (String value : values) {
            if (isComputeExpression(value)) {
                hasCompute = true;
                computeRef = value.substring(1, value.length() - 1);
            }
        }
        if (!hasCompute) {
            return;
        }
        if (values.length != 1) {
            throw definitionError(iface, target,
                    "@ClientHeaderParam('" + ann.name() + "') ne peut pas mélanger une compute method avec d'autres valeurs");
        }
        if (!hasValidComputeMethod(iface, computeRef)) {
            throw definitionError(iface, target,
                    "Compute method invalide ou introuvable pour @ClientHeaderParam('" + ann.name() + "'): " + computeRef);
        }
    }

    private static boolean hasValidComputeMethod(Class<?> iface, String computeRef) {
        int lastDot = computeRef.lastIndexOf('.');
        if (lastDot > 0) {
            String fqcn = computeRef.substring(0, lastDot);
            String methodName = computeRef.substring(lastDot + 1);
            try {
                Class<?> ext = Class.forName(fqcn, false, iface.getClassLoader());
                for (Method m : ext.getMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (!m.getName().equals(methodName)) continue;
                    if (isValidComputeSignature(m)) return true;
                }
                return false;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
        for (Method m : iface.getMethods()) {
            if (!m.getName().equals(computeRef)) continue;
            if (isValidComputeSignature(m)) return true;
        }
        for (Method m : iface.getDeclaredMethods()) {
            if (!m.getName().equals(computeRef)) continue;
            if (isValidComputeSignature(m)) return true;
        }
        return false;
    }

    private static boolean isValidComputeSignature(Method method) {
        if (!(method.getReturnType() == String.class || method.getReturnType() == String[].class)) {
            return false;
        }
        int paramCount = method.getParameterCount();
        if (paramCount == 0) return true;
        return paramCount == 1 && method.getParameterTypes()[0] == String.class;
    }

    private static boolean isComputeExpression(String value) {
        return value != null && value.startsWith("{") && value.endsWith("}") && value.length() > 2;
    }

    private static IllegalArgumentException definitionError(Class<?> iface, Object target, String message) {
        String location = target instanceof Method m
                ? iface.getName() + "#" + m.getName()
                : iface.getName();
        return new IllegalArgumentException(message + " (" + location + ")");
    }

    private static String extractPath(Class<?> c) {
        Path p = c.getAnnotation(Path.class);
        return p == null ? "" : p.value();
    }

    private static String extractPath(Method m) {
        Path p = m.getAnnotation(Path.class);
        return p == null ? "" : p.value();
    }

    private static String normalize(String segment) {
        if (segment == null || segment.isEmpty()) return "";
        String s = segment;
        if (!s.startsWith("/")) s = "/" + s;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String joinPath(String base, String sub) {
        if (sub.isEmpty()) return base.isEmpty() ? "/" : base;
        if (base.isEmpty()) return sub;
        // normalize() keeps "/" as-is; sub always starts with "/" — avoid double slash
        if (base.equals("/")) return sub;
        return base + sub;
    }

    private static List<String> extractMediaTypes(Consumes c) {
        return c == null ? List.of() : List.of(c.value());
    }

    private static List<String> extractMediaTypes(Produces p) {
        return p == null ? List.of() : List.of(p.value());
    }

    private static void collectClientHeaders(ClientHeaderParam[] anns,
                                             Map<String, List<String>> staticOut,
                                             Map<String, RequestSpec.DynamicHeader> dynamicOut) {
        for (ClientHeaderParam h : anns) {
            String[] values = h.value();
            // Si UNE valeur de la forme "{methodName}" → dynamique ; sinon statique.
            // Spec §6.5 : un header au même nom remplace une déclaration précédente
            // (et bascule statique↔dynamique selon le cas).
            if (values.length == 1 && values[0].startsWith("{") && values[0].endsWith("}")) {
                String methodName = values[0].substring(1, values[0].length() - 1);
                dynamicOut.put(h.name(), new RequestSpec.DynamicHeader(methodName, h.required()));
                staticOut.remove(h.name());
            } else {
                staticOut.put(h.name(), List.of(values));
                dynamicOut.remove(h.name());
            }
        }
    }

    private static List<ParamBinding> extractBindings(Method m) {
        Parameter[] params = m.getParameters();
        List<ParamBinding> out = new ArrayList<>(params.length);
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            String dflt = defaultOf(p);
            PathParam path = p.getAnnotation(PathParam.class);
            if (path != null) { out.add(new ParamBinding.Path(i, path.value(), dflt)); continue; }
            QueryParam query = p.getAnnotation(QueryParam.class);
            if (query != null) { out.add(new ParamBinding.Query(i, query.value(), dflt)); continue; }
            HeaderParam header = p.getAnnotation(HeaderParam.class);
            if (header != null) { out.add(new ParamBinding.Header(i, header.value(), dflt)); continue; }
            CookieParam cookie = p.getAnnotation(CookieParam.class);
            if (cookie != null) { out.add(new ParamBinding.Cookie(i, cookie.value(), dflt)); continue; }
            FormParam form = p.getAnnotation(FormParam.class);
            if (form != null) { out.add(new ParamBinding.Form(i, form.value(), dflt)); continue; }
            MatrixParam matrix = p.getAnnotation(MatrixParam.class);
            if (matrix != null) { out.add(new ParamBinding.Matrix(i, matrix.value(), dflt)); continue; }
            BeanParam bean = p.getAnnotation(BeanParam.class);
            if (bean != null) { out.add(new ParamBinding.Bean(i, extractFieldBindings(p.getType()))); continue; }
            // Aucune annotation reconnue → corps de requête (spec §3.1 — un seul body par méthode)
            out.add(new ParamBinding.Body(i));
        }
        return out;
    }

    private static String defaultOf(Parameter p) {
        DefaultValue dv = p.getAnnotation(DefaultValue.class);
        return dv == null ? null : dv.value();
    }

    private static List<ParamBinding.FieldBinding> extractFieldBindings(Class<?> beanType) {
        List<ParamBinding.FieldBinding> out = new ArrayList<>();
        Class<?> c = beanType;
        // remonter la hiérarchie pour récupérer les champs annotés
        var seen = new LinkedHashSet<String>();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!seen.add(f.getName())) continue;
                String dflt = f.isAnnotationPresent(DefaultValue.class)
                        ? f.getAnnotation(DefaultValue.class).value() : null;
                if (f.isAnnotationPresent(PathParam.class)) {
                    out.add(fb(f, ParamBinding.FieldBinding.Kind.PATH, f.getAnnotation(PathParam.class).value(), dflt));
                } else if (f.isAnnotationPresent(QueryParam.class)) {
                    out.add(fb(f, ParamBinding.FieldBinding.Kind.QUERY, f.getAnnotation(QueryParam.class).value(), dflt));
                } else if (f.isAnnotationPresent(HeaderParam.class)) {
                    out.add(fb(f, ParamBinding.FieldBinding.Kind.HEADER, f.getAnnotation(HeaderParam.class).value(), dflt));
                } else if (f.isAnnotationPresent(CookieParam.class)) {
                    out.add(fb(f, ParamBinding.FieldBinding.Kind.COOKIE, f.getAnnotation(CookieParam.class).value(), dflt));
                } else if (f.isAnnotationPresent(FormParam.class)) {
                    out.add(fb(f, ParamBinding.FieldBinding.Kind.FORM, f.getAnnotation(FormParam.class).value(), dflt));
                } else if (f.isAnnotationPresent(MatrixParam.class)) {
                    out.add(fb(f, ParamBinding.FieldBinding.Kind.MATRIX, f.getAnnotation(MatrixParam.class).value(), dflt));
                }
            }
            c = c.getSuperclass();
        }
        return out;
    }

    private static ParamBinding.FieldBinding fb(Field f, ParamBinding.FieldBinding.Kind kind, String name, String dflt) {
        // setAccessible: cyrano-core requires user beans to be accessible — pour M2 on accepte
        // le coût (les beans sont en général des POJOs publics ouverts au framework JAX-RS).
        try { f.setAccessible(true); } catch (RuntimeException ignored) { /* champ déjà accessible */ }
        return new ParamBinding.FieldBinding(f, kind, name, dflt);
    }

    /** Utilitaire test : noms canoniques des annotations de paramètre supportées. */
    static List<String> supportedAnnotationsForDocs() {
        return Arrays.asList(PathParam.class.getName(), QueryParam.class.getName(),
                HeaderParam.class.getName(), CookieParam.class.getName(),
                FormParam.class.getName(), MatrixParam.class.getName(),
                BeanParam.class.getName(), DefaultValue.class.getName());
    }
}
