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

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbTransient;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.ext.AsyncInvocationInterceptor;
import org.eclipse.microprofile.rest.client.ext.AsyncInvocationInterceptorFactory;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Heart of runtime invocation — called by the generated proxy (Class-File API) for
 * each method of the client interface.
 *
 * <p>Public contract referenced by the bytecode generated in another package. The
 * {@link #invoke(Object, int, Object[])} signature is the only contract called by proxies —
 * do not break it without aligning {@code CyranoProxyGenerator} accordingly.</p>
 *
 * <p>Spec MicroProfile Rest Client 4.0 §3 (invocation), §3.1 (parameter binding),
 * §4.2 (MessageBody), §5 (providers), §6.5 ({@code @ClientHeaderParam}),
 * §8 (default exception mapping).</p>
 */
public class CyranoInvocationHandler implements io.vidocq.cyrano.spi.gen.ClientInvoker {

    private static final System.Logger LOG = System.getLogger(CyranoInvocationHandler.class.getName());

    private final URI baseUri;
    private final List<RequestSpec> specs;
    private final CyranoHttpTransport transport;
    private final CyranoClientConfiguration configuration;
    private volatile boolean closed = false;

    public CyranoInvocationHandler(URI baseUri, List<RequestSpec> specs, CyranoHttpTransport transport) {
        this(baseUri, specs, transport, new CyranoClientConfiguration());
    }

    public CyranoInvocationHandler(URI baseUri, List<RequestSpec> specs,
                                   CyranoHttpTransport transport,
                                   CyranoClientConfiguration configuration) {
        this.baseUri = baseUri;
        this.specs = List.copyOf(specs);
        this.transport = transport;
        this.configuration = configuration;
    }

    /**
     * Single entry point called by each method of the generated proxy.
     *
     * @param proxy the proxy instance itself — used to invoke the
     * {@code default} methods referenced by {@code @ClientHeaderParam}
     * @param methodIndex method index in the {@code specs} list
     * @param args arguments passed by the caller
     */
    /** Spec §8.1 — called by the synthetic close() of the proxy. */
    @Override
    public void markClosed() {
        this.closed = true;
    }

    @Override
    public Object invoke(Object proxy, int methodIndex, Object[] args) throws Exception {
        RequestSpec spec = specs.get(methodIndex);
        return io.vidocq.cyrano.runtime.ProviderInstantiator.current().aroundInvoke(
                proxy,
                spec.method(),
                args,
                () -> invokeInternal(proxy, methodIndex, args, spec));
    }

    private Object invokeInternal(Object proxy, int methodIndex, Object[] args, RequestSpec spec) throws Exception {
        if (closed) {
            throw new IllegalStateException(
                    "This REST client has been closed — MP Rest Client 4.0 spec §8.1");
        }
        boolean asyncReturn = CompletionStage.class.isAssignableFrom(spec.returnType());

        //Sub-resource locator: httpMethod == null → return a new proxy for the sub-interface
        if (spec.httpMethod() == null) {
            return buildSubResourceProxy(spec, args);
        }

        //1. Resolve binding values (by expanding @BeanParam)
        ResolvedBindings r = resolveBindings(spec, args);

        // 2. Build URI (path templates + query + matrix)
        URI uri = buildUri(spec, r);

        // 3. Build the initial body (form-encoded takes precedence if @FormParam, otherwise JSON-B)
        BodyPayload body = buildBody(spec, r);

        //4. Prepare filtered context (MP Rest Client §4.2 + JAX-RS §6.3, iteration M4-2)
        CyranoClientRequestContext reqCtx = new CyranoClientRequestContext(uri, spec.httpMethod(), configuration);
        seedHeaders(reqCtx, spec, r, proxy, body);
        seedEntity(reqCtx, spec, r, body);
        //MP Rest Client §4.2 — standard filter property
        reqCtx.setProperty("org.eclipse.microprofile.rest.client.invokedMethod", spec.method());

        //5. Pipeline ClientRequestFilter (top-up priorities)
        var reqFilters = configuration.getRequestFilters();
        if (Boolean.getBoolean("cyrano.debug.providers")) {
            LOG.log(System.Logger.Level.DEBUG, () -> "req filters for " + spec.method().getName()
                    + " (" + reqFilters.size() + ") = "
                    + reqFilters.stream().map(f -> f.getClass().getSimpleName()).toList());
        }
        for (var f : reqFilters) {
            try {
                f.filter(reqCtx);
            } catch (IOException ioe) {
                throw new jakarta.ws.rs.ProcessingException(ioe);
            }
            if (reqCtx.isAborted()) break;
        }

        //6. Either we've been aborted or we're actually sending the request.
        if (asyncReturn) {
            Type asyncGenericType = innerType(spec.genericReturnType());
            Class<?> asyncRawType = rawType(asyncGenericType);
            List<AsyncInvocationInterceptor> asyncInterceptors = newAsyncInterceptors();
            for (AsyncInvocationInterceptor interceptor : asyncInterceptors) {
                try { interceptor.prepareContext(); } catch (RuntimeException ignored) {}
            }
            if (reqCtx.isAborted()) {
                try {
                    for (AsyncInvocationInterceptor interceptor : asyncInterceptors) {
                        try { interceptor.applyContext(); } catch (RuntimeException ignored) {}
                    }
                    CyranoClientResponseContext respCtx = CyranoClientResponseContext.fromAbort(reqCtx.abortResponse());
                    for (var f : configuration.getResponseFilters()) {
                        try {
                            f.filter(reqCtx, respCtx);
                        } catch (IOException ioe) {
                            throw new jakarta.ws.rs.ProcessingException(ioe);
                        }
                    }
                    Object mapped = mapResponse(respCtx, spec, asyncRawType, asyncGenericType);
                    return CompletableFuture.completedFuture(mapped);
                } finally {
                    for (AsyncInvocationInterceptor interceptor : asyncInterceptors) {
                        try { interceptor.removeContext(); } catch (RuntimeException ignored) {}
                    }
                }
            }

            HttpRequest finalReq = buildHttpRequest(reqCtx);
            return transport.sendAsync(finalReq).thenApplyAsync(resp -> {
                for (AsyncInvocationInterceptor interceptor : asyncInterceptors) {
                    try { interceptor.applyContext(); } catch (RuntimeException ignored) {}
                }
                try {
                    CyranoClientResponseContext respCtx = CyranoClientResponseContext.of(resp);
                    for (var f : configuration.getResponseFilters()) {
                        try {
                            f.filter(reqCtx, respCtx);
                        } catch (IOException ioe) {
                            throw new jakarta.ws.rs.ProcessingException(ioe);
                        }
                    }
                    return mapResponse(respCtx, spec, asyncRawType, asyncGenericType);
                } finally {
                    for (AsyncInvocationInterceptor interceptor : asyncInterceptors) {
                        try { interceptor.removeContext(); } catch (RuntimeException ignored) {}
                    }
                }
            }, asyncCallbackExecutor());
        }

        CyranoClientResponseContext respCtx;
        if (reqCtx.isAborted()) {
            respCtx = CyranoClientResponseContext.fromAbort(reqCtx.abortResponse());
        } else {
            HttpRequest finalReq = buildHttpRequest(reqCtx);
            HttpResponse<String> resp;
            try {
                resp = transport.send(finalReq);
            } catch (IOException ioe) {
                throw new jakarta.ws.rs.ProcessingException(ioe);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new jakarta.ws.rs.ProcessingException(ie);
            }
            respCtx = CyranoClientResponseContext.of(resp);
        }

        //7. Pipeline ClientResponseFilter (top-down priorities)
        for (var f : configuration.getResponseFilters()) {
            try {
                f.filter(reqCtx, respCtx);
            } catch (IOException ioe) {
                throw new jakarta.ws.rs.ProcessingException(ioe);
            }
        }

        // 8. Final mapping of the return value
        return mapResponse(respCtx, spec);
    }

    // ============================================================
    // Build the HttpRequest from the final context (post-filters)
    // ============================================================
    private HttpRequest buildHttpRequest(CyranoClientRequestContext ctx) {
        HttpRequest.Builder b = HttpRequest.newBuilder(ctx.getUri());
        if (configuration.getReadTimeoutMs() > 0) {
            b.timeout(Duration.ofMillis(configuration.getReadTimeoutMs()));
        }
        //Headers — prohibited by JDK HttpClient: Host, Connection, etc. Keep
        // everything else, and silently ignore rejections.
        for (var e : ctx.getStringHeaders().entrySet()) {
            for (String v : e.getValue()) {
                try { b.header(e.getKey(), v); }
                catch (IllegalArgumentException ignored) { /* restricted header */ }
            }
        }
        HttpRequest.BodyPublisher pub;
        Object entity = ctx.getEntity();
        if (entity == null) {
            pub = HttpRequest.BodyPublishers.noBody();
        } else if (entity instanceof byte[] bytes) {
            pub = HttpRequest.BodyPublishers.ofByteArray(bytes);
        } else {
            //Apply registered MessageBodyWriter (+interceptors) if applicable (spec §4.2 §6.5)
            @SuppressWarnings({"rawtypes", "unchecked"})
            MessageBodyWriter writer = findMessageBodyWriter(entity.getClass());
            List<WriterInterceptor> writerInterceptors = configuration.getWriterInterceptors();
            if (writer != null) {
                String ctHeader = ctx.getHeaderString("Content-Type");
                MediaType mediaType = parseMediaType(ctHeader);
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    MultivaluedHashMap<String, Object> msgHeaders = new MultivaluedHashMap<>();
                    if (!writerInterceptors.isEmpty()) {
                        new CyranoWriterInterceptorContext(writerInterceptors, writer,
                                entity, entity.getClass(), entity.getClass(),
                                new Annotation[0], mediaType, msgHeaders, baos).proceed();
                    } else {
                        writer.writeTo(entity, entity.getClass(), entity.getClass(),
                                new Annotation[0], mediaType, msgHeaders, baos);
                    }
                    pub = HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray());
                } catch (IOException e) {
                    throw new jakarta.ws.rs.ProcessingException(e);
                }
            } else if (entity instanceof String s) {
                pub = HttpRequest.BodyPublishers.ofString(s);
            } else {
                String json = jsonbFor(entity.getClass()).toJson(entity);
                pub = HttpRequest.BodyPublishers.ofString(json);
            }
        }
        b.method(ctx.getMethod(), pub);
        return b.build();
    }

    // ============================================================
    //Step 1: Binding resolution (BeanParam expanded)
    // ============================================================
    private record ResolvedBindings(
            Map<String, String> pathParams,
            Map<String, List<String>> queryParams,
            Map<String, String> matrixParams,
            Map<String, List<String>> headers,
            Map<String, String> cookies,
            Map<String, String> formParams,
            Object body,
            boolean hasBody) {
    }

    private static ResolvedBindings resolveBindings(RequestSpec spec, Object[] args) {
        Map<String, String> pathParams = new LinkedHashMap<>();
        Map<String, List<String>> queryParams = new LinkedHashMap<>();
        Map<String, String> matrixParams = new LinkedHashMap<>();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Map<String, String> cookies = new LinkedHashMap<>();
        Map<String, String> formParams = new LinkedHashMap<>();
        Object body = null;
        boolean hasBody = false;

        for (ParamBinding pb : spec.bindings()) {
            switch (pb) {
                case ParamBinding.Path p -> pathParams.put(p.name(), valueOrDefault(args[p.paramIndex()], p.defaultValue()));
                case ParamBinding.Query q -> {
                    Object qVal = args == null ? null : args[q.paramIndex()];
                    if (qVal instanceof java.util.Collection<?> col) {
                        for (Object item : col) addMulti(queryParams, q.name(), item == null ? null : stringify(item));
                    } else if (qVal != null && qVal.getClass().isArray()) {
                        for (Object item : (Object[]) qVal) addMulti(queryParams, q.name(), item == null ? null : stringify(item));
                    } else {
                        addMulti(queryParams, q.name(), valueOrDefault(qVal, q.defaultValue()));
                    }
                }
                case ParamBinding.Header h -> addMulti(headers, h.name(), valueOrDefault(args[h.paramIndex()], h.defaultValue()));
                case ParamBinding.Cookie c -> cookies.put(c.name(), valueOrDefault(args[c.paramIndex()], c.defaultValue()));
                case ParamBinding.Form f -> formParams.put(f.name(), valueOrDefault(args[f.paramIndex()], f.defaultValue()));
                case ParamBinding.Matrix mx -> matrixParams.put(mx.name(), valueOrDefault(args[mx.paramIndex()], mx.defaultValue()));
                case ParamBinding.Body bd -> {
                    body = args[bd.paramIndex()];
                    hasBody = true;
                }
                case ParamBinding.Bean bean -> {
                    Object beanObj = args[bean.paramIndex()];
                    if (beanObj != null) {
                        for (ParamBinding.FieldBinding fb : bean.fields()) {
                            Object v;
                            try { v = fb.field().get(beanObj); }
                            catch (IllegalAccessException ex) {
                                throw new IllegalStateException(
                                        "@BeanParam.field access denied: " + fb.field(), ex);
                            }
                            String str = valueOrDefault(v, fb.defaultValue());
                            if (str == null) continue;
                            switch (fb.kind()) {
                                case PATH -> pathParams.put(fb.name(), str);
                                case QUERY -> addMulti(queryParams, fb.name(), str);
                                case HEADER -> addMulti(headers, fb.name(), str);
                                case COOKIE -> cookies.put(fb.name(), str);
                                case FORM -> formParams.put(fb.name(), str);
                                case MATRIX -> matrixParams.put(fb.name(), str);
                            }
                        }
                    }
                }
            }
        }
        return new ResolvedBindings(pathParams, queryParams, matrixParams, headers, cookies, formParams, body, hasBody);
    }

    private static String valueOrDefault(Object v, String dflt) {
        if (v != null) return stringify(v);
        return dflt; //may be null
    }

    private static String stringify(Object v) {
        return v == null ? "" : v.toString();
    }

    private static <K, V> void addMulti(Map<K, List<V>> map, K key, V value) {
        if (value == null) return;
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    // ============================================================
    //Step 2: Construction of URI
    // ============================================================
    private URI buildUri(RequestSpec spec, ResolvedBindings r) {
        String path = spec.pathTemplate();
        for (var e : r.pathParams().entrySet()) {
            String token = "{" + e.getKey() + "}";
            String enc = e.getValue() == null ? "" : encodePathParam(e.getValue());
            //PathParam: Keeps valid pchars (including ':') and '/' for subpaths.
            path = path.replace(token, enc);
        }
        //Matrix params: added to the last segment of the path (before ?query)
        if (!r.matrixParams().isEmpty()) {
            StringBuilder sb = new StringBuilder(path);
            for (var e : r.matrixParams().entrySet()) {
                if (e.getValue() == null) continue;
                sb.append(';').append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                        .append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
            path = sb.toString();
        }
        //Query string — spec MP Rest Client §3.4 QueryParamStyle
        String query = "";
        if (!r.queryParams().isEmpty()) {
            var style = configuration.getQueryParamStyle();
            List<String> pairs = new ArrayList<>();
            for (var e : r.queryParams().entrySet()) {
                String rawKey = e.getKey();
                List<String> values = e.getValue().stream().filter(v -> v != null).toList();
                if (values.isEmpty()) continue;
                switch (style) {
                    case COMMA_SEPARATED -> {
                        String key = URLEncoder.encode(rawKey, StandardCharsets.UTF_8);
                        String joined = values.stream()
                                .map(v -> URLEncoder.encode(v, StandardCharsets.UTF_8))
                                .collect(java.util.stream.Collectors.joining(","));
                        pairs.add(key + "=" + joined);
                    }
                    case ARRAY_PAIRS -> {
                        // RFC note: [] are technically invalid in URIs but widely used in practice.
                        //Don't URL-encode [] — Java's URI(scheme, ssp, null) preserves them.
                        String key = URLEncoder.encode(rawKey, StandardCharsets.UTF_8) + "[]";
                        for (String v : values) pairs.add(key + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8));
                    }
                    default -> { // MULTI_PAIRS
                        String key = URLEncoder.encode(rawKey, StandardCharsets.UTF_8);
                        for (String v : values) pairs.add(key + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8));
                    }
                }
            }
            if (!pairs.isEmpty()) query = "?" + String.join("&", pairs);
        }
        String base = baseUri.toString();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        //If the path is just "/" (explicit @Path method), do not add a slash
        //Final that is not part of the contract (ConfigKeyForMultipleInterfacesTest §6.5).
        if ("/".equals(path) && query.isEmpty()) path = "";
        return buildFinalUri(base + path + query);
    }

    private static String encodePathParam(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (isPathParamByteAllowed(c)) {
                out.append((char) c);
            } else {
                out.append('%');
                char hi = Character.toUpperCase(Character.forDigit((c >>> 4) & 0xF, 16));
                char lo = Character.toUpperCase(Character.forDigit(c & 0xF, 16));
                out.append(hi).append(lo);
            }
        }
        return out.toString();
    }

    private static boolean isPathParamByteAllowed(int c) {
        // RFC 3986 pchar = unreserved / sub-delims / ':' / '@'.
        //Cyrano also keeps '/' to allow @PathParam subpaths.
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) return true;
        return c == '-' || c == '.' || c == '_' || c == '~'
                || c == '!' || c == '$' || c == '&' || c == '\'' || c == '(' || c == ')'
                || c == '*' || c == '+' || c == ',' || c == ';' || c == '='
                || c == ':' || c == '@' || c == '/';
    }

    /**
     * Creates a URI from a raw chain that can contain {@code []} (ARRAY PAIRS style).
     * Java's {@code URI.create()} rejette {@code []} en query string (non RFC 3986).
     * We first try {@code URI(scheme, ssp, null)} that can preserve the hooks
     * in the text representation, otherwise we encode in {@code %5B%5D}.
     */
    private static URI buildFinalUri(String rawUrl) {
        try {
            return URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            // [] in query string: try the scheme+ssp constructor which preserves raw chars
            int colon = rawUrl.indexOf(':');
            if (colon > 0) {
                try {
                    return new java.net.URI(rawUrl.substring(0, colon), rawUrl.substring(colon + 1), null);
                } catch (java.net.URISyntaxException e2) {
                    // Final fallback: percent-encode []
                    return URI.create(rawUrl.replace("[", "%5B").replace("]", "%5D"));
                }
            }
            return URI.create(rawUrl.replace("[", "%5B").replace("]", "%5D"));
        }
    }

    // ============================================================
    //Step 3: Body Building
    // ============================================================
    private record BodyPayload(HttpRequest.BodyPublisher publisher, String contentType) {}

    private BodyPayload buildBody(RequestSpec spec, ResolvedBindings r) {
        //(a) Form-encoded — priority if @FormParam present
        if (!r.formParams().isEmpty()) {
            List<String> pairs = new ArrayList<>();
            for (var e : r.formParams().entrySet()) {
                if (e.getValue() == null) continue;
                pairs.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
            String form = String.join("&", pairs);
            return new BodyPayload(HttpRequest.BodyPublishers.ofString(form),
                    MediaType.APPLICATION_FORM_URLENCODED);
        }
        //(b) Body POJO — content-type determined here; current serialization in buildHttpRequest()
        // (MessageBodyWriter + interceptors run post-filters on the final entity from context)
        if (r.hasBody() && r.body() != null) {
            String ct = firstConsumesOrJson(spec);
            return new BodyPayload(HttpRequest.BodyPublishers.noBody(), ct);
        }
        return new BodyPayload(HttpRequest.BodyPublishers.noBody(), null);
    }

    private static String firstConsumesOrJson(RequestSpec spec) {
        return spec.consumes().isEmpty() ? MediaType.APPLICATION_JSON : spec.consumes().get(0);
    }

    // ============================================================
    //Step 4: Seed headers + entity in context (pre-filters)
    // ============================================================
    private void seedHeaders(CyranoClientRequestContext ctx, RequestSpec spec, ResolvedBindings r,
                             Object proxy, BodyPayload body) {
        var headers = ctx.getHeaders();
        //Accept (@Produces) — spec §4.1: default is application/json when no @Produces
        if (!spec.produces().isEmpty()) {
            headers.putSingle("Accept", String.join(", ", spec.produces()));
        } else {
            headers.putSingle("Accept", MediaType.APPLICATION_JSON);
        }
        //Content-Type via @Consumes or derived from the original body
        if (body.contentType() != null) {
            headers.putSingle("Content-Type", body.contentType());
        }
        //@ClientHeaderParam static — replaced by @HeaderParam at runtime with the same name (spec §6.5)
        Set<String> runtimeOverrides = r.headers().keySet();
        for (var e : spec.staticHeaders().entrySet()) {
            if (runtimeOverrides.contains(e.getKey())) continue;
            for (String v : e.getValue()) headers.add(e.getKey(), v);
        }
        // @ClientHeaderParam dynamic
        for (var e : spec.dynamicHeaders().entrySet()) {
            if (runtimeOverrides.contains(e.getKey())) continue;
            RequestSpec.DynamicHeader dh = e.getValue();
            String value;
            try {
                value = invokeHeaderMethod(proxy, spec.method().getDeclaringClass(),
                        dh.methodName(), e.getKey());
            } catch (RuntimeException ex) {
                if (dh.required()) throw ex;
                continue;
            }
            if (value != null) headers.add(e.getKey(), value);
        }
        //Headers installed via RestClientBuilder.header() — spec §3.2, low priority (overridden by @HeaderParam)
        for (var e : configuration.getBuilderHeaders().entrySet()) {
            if (!r.headers().containsKey(e.getKey())) {
                headers.add(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        // @HeaderParam runtime
        for (var e : r.headers().entrySet()) {
            for (String v : e.getValue()) {
                if (v != null) headers.add(e.getKey(), v);
            }
        }
        //@CookieParam → combined "Cookie" header
        if (!r.cookies().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (var e : r.cookies().entrySet()) {
                if (e.getValue() == null) continue;
                if (!first) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            if (sb.length() > 0) headers.putSingle("Cookie", sb.toString());
        }

        applyClientHeadersFactory(spec, headers);
    }

    private void applyClientHeadersFactory(RequestSpec spec, MultivaluedMap<String, Object> headers) {
        RegisterClientHeaders ann = spec.method().getDeclaringClass().getAnnotation(RegisterClientHeaders.class);
        if (ann == null) return;

        Object instance = io.vidocq.cyrano.runtime.ProviderInstantiator.current().create(ann.value());
        if (instance == null) {
            instance = io.vidocq.cyrano.runtime.ProviderInstantiator.defaultInstantiator().create(ann.value());
        }
        if (!(instance instanceof ClientHeadersFactory factory)) {
            return;
        }

        MultivaluedMap<String, String> outgoing = new MultivaluedHashMap<>();
        for (var e : headers.entrySet()) {
            for (Object value : e.getValue()) {
                if (value != null) {
                    outgoing.add(e.getKey(), String.valueOf(value));
                }
            }
        }

        MultivaluedMap<String, String> updated = factory.update(new MultivaluedHashMap<>(), outgoing);
        if (updated == null) return;
        headers.clear();
        for (var e : updated.entrySet()) {
            for (String value : e.getValue()) {
                headers.add(e.getKey(), value);
            }
        }
    }

    /** Stores the business entity in the context (POJO for JSON-B; form-encoded String for @FormParam). */
    private void seedEntity(CyranoClientRequestContext ctx, RequestSpec spec, ResolvedBindings r, BodyPayload body) {
        if (!r.formParams().isEmpty()) {
            //For form-encoded, the already encoded string is stored as an entity — a filter
            // can replace it if desired.
            String form = encodeForm(r.formParams());
            ctx.setEntityInternal(form, String.class, String.class);
        } else if (r.hasBody() && r.body() != null) {
            //POJO to be serialized via JSON-B at dispatch time.
            ctx.setEntityInternal(r.body(), r.body().getClass(), r.body().getClass());
        }
    }

    private static String encodeForm(Map<String, String> formParams) {
        List<String> pairs = new ArrayList<>();
        for (var e : formParams.entrySet()) {
            if (e.getValue() == null) continue;
            pairs.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return String.join("&", pairs);
    }

    // ============================================================
    //(legacy removed: applyHeaders → replaced by seeHeaders + buildHttpRequest)
    // ============================================================

    private static String invokeHeaderMethod(Object proxy, Class<?> iface, String methodName, String headerName) {
        //Spec §6.5: allowed signature = no arg / string (header name).
        Method dm = findHeaderMethod(iface, methodName);
        if (dm == null) {
            throw new IllegalStateException("@ClientHeaderParam method not found: "
                    + iface.getName() + "#" + methodName + " — spec §6.5");
        }
        Class<?>[] pt = dm.getParameterTypes();
        Object result;
        try {
            if (Modifier.isStatic(dm.getModifiers())) {
                result = dm.invoke(null, pt.length == 0 ? new Object[0] : new Object[]{headerName});
            } else {
                result = dm.invoke(proxy, pt.length == 0 ? new Object[0] : new Object[]{headerName});
            }
        } catch (java.lang.reflect.InvocationTargetException ite) {
            //Spec §6.5: the caller must see the exception as-is (or the header is
            //silently omitted if required=false — decided upstream by applyHeaders).
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException("@ClientHeaderParam(" + methodName + ") failed",
                    cause != null ? cause : ite);
        } catch (IllegalAccessException iae) {
            throw new IllegalStateException("Access denied to @ClientHeaderParam("
                    + methodName + ") sur " + iface.getName(), iae);
        }
        if (result == null) return null;
        if (result instanceof String[] arr) {
            return String.join(",", arr);
        }
        return result.toString();
    }

    private static Method findHeaderMethod(Class<?> iface, String name) {
        //Spec §6.5 — FQN reference to an external static method:
        // {@code @ClientHeaderParam(value="{com.foo.Util.compute}")}.
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            String fqcn = name.substring(0, lastDot);
            String mname = name.substring(lastDot + 1);
            try {
                Class<?> ext = Class.forName(fqcn, false, iface.getClassLoader());
                Method bestMatch = null;
                for (Method m : ext.getMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (!m.getName().equals(mname)) continue;
                    int pc = m.getParameterCount();
                    if (pc == 0) return m;
                    if (pc == 1 && m.getParameterTypes()[0] == String.class) bestMatch = m;
                }
                return bestMatch;
            } catch (ClassNotFoundException ignored) {
                // fall back to the local lookup below
            }
        }
        for (Method m : iface.getMethods()) {
            if (!m.getName().equals(name)) continue;
            int pc = m.getParameterCount();
            if (pc == 0) return m;
            if (pc == 1 && m.getParameterTypes()[0] == String.class) return m;
        }
        //search in declared methods (edge cases)
        for (Method m : iface.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    // ============================================================
    //Step 5: mapping response + exception mapping
    // ============================================================
    private Object mapResponse(CyranoClientResponseContext respCtx, RequestSpec spec) {
        return mapResponse(respCtx, spec, null, null);
    }

    private Object mapResponse(CyranoClientResponseContext respCtx,
                               RequestSpec spec,
                               Class<?> overrideReturnType,
                               Type overrideGenericType) {
        int status = respCtx.getStatus();
        String body = respCtx.readBodyAsString();
        Class<?> rt = overrideReturnType != null ? overrideReturnType : spec.returnType();
        Type genericType = overrideGenericType != null ? overrideGenericType : spec.genericReturnType();

        //User-registered ResponseExceptionMapper run for ALL status codes (spec §5.4).
        //A mapper can handle any status — e.g. TestResponseExceptionMapper handles 200.
        Optional<RuntimeException> userEx = applyExceptionMappers(respCtx, body, spec);
        if (userEx.isPresent()) throw userEx.get();

        if (status >= 400) {
            if (rt == Optional.class && status == 404) {
                return Optional.empty();
            }
            //spec §8: microprofile.rest.client.disable.default.mapper=true → return raw response
            Object disableProp = configuration.getProperty("microprofile.rest.client.disable.default.mapper");
            if (disableProp == null) {
                disableProp = System.getProperty("microprofile.rest.client.disable.default.mapper");
            }
            boolean defaultMapperDisabled = Boolean.TRUE.equals(disableProp)
                    || "true".equalsIgnoreCase(String.valueOf(disableProp));
            if (defaultMapperDisabled) {
                if (rt == Response.class) return CyranoLightResponse.of(respCtx, body, configuration);
                if (rt == void.class || rt == Void.class) return null;
                return CyranoLightResponse.of(respCtx, body, configuration);
            }
            throw defaultException(respCtx, body);
        }

        if (rt == void.class || rt == Void.class) return null;
        //Response return type — pass configuration so readEntity() can use registered readers
        if (rt == Response.class) return CyranoLightResponse.of(respCtx, body, configuration);

        //MessageBodyReader (+interceptors) — check registered readers before the default path
        @SuppressWarnings({"rawtypes", "unchecked"})
        MessageBodyReader reader = findMessageBodyReader(rt);
        if (reader != null && body != null) {
            MediaType mediaType = extractResponseMediaType(respCtx);
            List<ReaderInterceptor> readerInterceptors = configuration.getReaderInterceptors();
            try {
                var is = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
                if (!readerInterceptors.isEmpty()) {
                    return new CyranoReaderInterceptorContext(readerInterceptors, reader, rt,
                            genericType, new Annotation[0], mediaType,
                            new MultivaluedHashMap<>(), is).proceed();
                } else {
                    return reader.readFrom(rt, genericType, new Annotation[0],
                            mediaType, new MultivaluedHashMap<>(), is);
                }
            } catch (IOException e) {
                throw new jakarta.ws.rs.ProcessingException(e);
            }
        }

        if (rt == String.class) return body;
        if (rt.isPrimitive() || Number.class.isAssignableFrom(rt)
                || rt == Boolean.class || rt == Character.class) {
            return parsePrimitive(body, rt);
        }
        if (rt == Optional.class) {
            Type t = innerType(genericType);
            Object value = readJson(body, t);
            return Optional.ofNullable(value);
        }
        if (rt == List.class || rt == java.util.Collection.class) {
            return readJson(body, genericType);
        }
        if (rt == Set.class) {
            Object asList = readJson(body, listOf(innerType(genericType)));
            return new LinkedHashSet<>((List<?>) asList);
        }
        if (rt == Map.class) {
            return readJson(body, genericType);
        }
        // POJO
        return readJson(body, genericType);
    }

    private Optional<RuntimeException> applyExceptionMappers(CyranoClientResponseContext respCtx,
                                                             String body, RequestSpec spec) {
        Response light = CyranoLightResponse.of(respCtx, body);
        //Collect all ResponseExceptionMapper saved via builder.register(...)
        List<MapperEntry> mappers = new ArrayList<>();
        for (Object inst : configuration.getInstances()) {
            if (inst instanceof ResponseExceptionMapper<?> rem) {
                mappers.add(new MapperEntry(rem, rem.getPriority()));
            }
        }
        mappers.sort(Comparator.comparingInt(MapperEntry::priority));
        for (MapperEntry e : mappers) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            ResponseExceptionMapper raw = e.mapper();
            if (raw.handles(light.getStatus(), light.getHeaders())) {
                @SuppressWarnings("unchecked")
                Throwable t = raw.toThrowable(light);
                if (t == null) continue;
                if (t instanceof RuntimeException re) return Optional.of(re);
                return Optional.of(new RuntimeException(t));
            }
        }
        return Optional.empty();
    }

    private record MapperEntry(ResponseExceptionMapper<?> mapper, int priority) {}

    private static WebApplicationException defaultException(CyranoClientResponseContext respCtx, String body) {
        //Spec §8: default mapper priority 1 → WebApplicationException based on Response.
        return new WebApplicationException("HTTP " + respCtx.getStatus(),
                CyranoLightResponse.of(respCtx, body));
    }

    private static Type innerType(Type t) {
        if (t instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length >= 1) return args[0];
        }
        return Object.class;
    }

    private static Class<?> rawType(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) return c;
        return Object.class;
    }

    private List<AsyncInvocationInterceptor> newAsyncInterceptors() {
        List<AsyncInvocationInterceptor> out = new ArrayList<>();
        for (Object inst : configuration.getInstances()) {
            if (inst instanceof AsyncInvocationInterceptorFactory factory) {
                try {
                    AsyncInvocationInterceptor interceptor = factory.newInterceptor();
                    if (interceptor != null) out.add(interceptor);
                } catch (RuntimeException ignored) {
                    //failed factory ignored
                }
            }
        }
        return out;
    }

    private Executor asyncCallbackExecutor() {
        Executor configured = configuration.getExecutorService();
        if (configured != null) {
            return configured;
        }
        return Runnable::run;
    }

    private static Type listOf(Type element) {
        return new ParameterizedType() {
            @Override public Type[] getActualTypeArguments() { return new Type[]{element}; }
            @Override public Type getRawType() { return List.class; }
            @Override public Type getOwnerType() { return null; }
            @Override public String getTypeName() { return "java.util.List<" + element.getTypeName() + ">"; }
        };
    }

    private Object readJson(String body, Type type) {
        if (body == null || body.isEmpty()) return null;
        Object mapped = jsonbFor(rawType(type)).fromJson(body, type);
        if (type instanceof Class<?> cls) {
            patchPrivateFieldsFromJson(mapped, cls, body);
        }
        return mapped;
    }

    private static void patchPrivateFieldsFromJson(Object target, Class<?> rawType, String body) {
        if (target == null || body == null || body.isBlank()) return;
        jakarta.json.JsonObject json;
        try (var reader = jakarta.json.Json.createReader(new java.io.StringReader(body))) {
            json = reader.readObject();
        } catch (RuntimeException ex) {
            return;
        }
        Class<?> cursor = rawType;
        while (cursor != null && cursor != Object.class) {
            for (var field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
                if (field.getAnnotation(JsonbTransient.class) != null) continue;
                String jsonName = field.getName();
                JsonbProperty named = field.getAnnotation(JsonbProperty.class);
                if (named != null && named.value() != null && !named.value().isBlank()) {
                    jsonName = named.value();
                }
                if (!json.containsKey(jsonName)) continue;
                Object value = jsonValue(json.get(jsonName), field.getType());
                if (value == null) continue;
                try {
                    MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(cursor, MethodHandles.lookup());
                    VarHandle handle = lookup.findVarHandle(cursor, field.getName(), field.getType());
                    handle.set(target, value);
                } catch (RuntimeException | ReflectiveOperationException ignored) {
                    //best-effort fallback only
                }
            }
            cursor = cursor.getSuperclass();
        }
    }

    private static Object jsonValue(jakarta.json.JsonValue value, Class<?> targetType) {
        if (value == null || value == jakarta.json.JsonValue.NULL) return null;
        if (targetType == String.class) {
            if (value.getValueType() == jakarta.json.JsonValue.ValueType.STRING) {
                return ((jakarta.json.JsonString) value).getString();
            }
            return value.toString();
        }
        if (targetType == int.class || targetType == Integer.class) {
            if (value.getValueType() == jakarta.json.JsonValue.ValueType.NUMBER) {
                return ((jakarta.json.JsonNumber) value).intValue();
            }
            return Integer.parseInt(value.toString());
        }
        if (targetType == long.class || targetType == Long.class) {
            if (value.getValueType() == jakarta.json.JsonValue.ValueType.NUMBER) {
                return ((jakarta.json.JsonNumber) value).longValue();
            }
            return Long.parseLong(value.toString());
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return value == jakarta.json.JsonValue.TRUE
                    || "true".equalsIgnoreCase(value.toString());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Jsonb jsonbFor(Class<?> targetType) {
        boolean debug = Boolean.getBoolean("cyrano.debug.jsonb");
        for (Object inst : configuration.getInstances()) {
            if (inst instanceof ContextResolver<?> resolver) {
                Object resolved;
                try {
                    resolved = ((ContextResolver<Object>) resolver).getContext(targetType);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (resolved instanceof Jsonb jsonb) {
                    if (debug) {
                        LOG.log(System.Logger.Level.DEBUG, () -> "Jsonb via ContextResolver="
                                + inst.getClass().getName() + " for " + targetType.getName());
                    }
                    return jsonb;
                }
            }
        }
        if (debug) {
            LOG.log(System.Logger.Level.DEBUG, () -> "Jsonb default for " + targetType.getName()
                    + " (instances=" + configuration.getInstances().size() + ")");
        }
        return JsonbHolder.get();
    }

    private static Object parsePrimitive(String body, Class<?> type) {
        String s = body == null ? "" : body.trim();
        if (type == int.class || type == Integer.class) return Integer.parseInt(s);
        if (type == long.class || type == Long.class) return Long.parseLong(s);
        if (type == short.class || type == Short.class) return Short.parseShort(s);
        if (type == byte.class || type == Byte.class) return Byte.parseByte(s);
        if (type == double.class || type == Double.class) return Double.parseDouble(s);
        if (type == float.class || type == Float.class) return Float.parseFloat(s);
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(s);
        if (type == char.class || type == Character.class) return s.isEmpty() ? '\0' : s.charAt(0);
        throw new UnsupportedOperationException("Unsupported primitive: " + type);
    }

    /** Find the first registered MessageBodyReader applicable to the given type. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private MessageBodyReader<?> findMessageBodyReader(Class<?> type) {
        MediaType wildcard = MediaType.WILDCARD_TYPE;
        for (Object inst : configuration.getInstances()) {
            if (inst instanceof MessageBodyReader r) {
                if (r.isReadable(type, type, new Annotation[0], wildcard)) {
                    return r;
                }
            }
        }
        return null;
    }

    /** Find the first registered MessageBodyWriter applicable to the given type. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private MessageBodyWriter<?> findMessageBodyWriter(Class<?> type) {
        MediaType wildcard = MediaType.WILDCARD_TYPE;
        for (Object inst : configuration.getInstances()) {
            if (inst instanceof MessageBodyWriter w) {
                if (w.isWriteable(type, type, new Annotation[0], wildcard)) {
                    return w;
                }
            }
        }
        return null;
    }

    private static MediaType parseMediaType(String ct) {
        if (ct == null || ct.isBlank()) return MediaType.APPLICATION_JSON_TYPE;
        try { return MediaType.valueOf(ct); }
        catch (IllegalArgumentException e) { return MediaType.APPLICATION_JSON_TYPE; }
    }

    private static MediaType extractResponseMediaType(CyranoClientResponseContext respCtx) {
        List<String> ct = respCtx.getHeaders().get("Content-Type");
        if (ct == null || ct.isEmpty()) return MediaType.WILDCARD_TYPE;
        return parseMediaType(ct.get(0));
    }

    /** Sub-resource locator — spec JAX-RS §3.7 / MP Rest Client §3: build a new proxy at the sub-path URI. */
    private Object buildSubResourceProxy(RequestSpec spec, Object[] args) {
        // Resolve path params in the sub-resource path template
        ResolvedBindings r = resolveBindings(spec, args);
        URI subUri = buildUri(spec, r);
        Class<?> subIface = spec.returnType();
        // Sub-resource proxies follow the same generated-first resolution chain (CG-01).
        var resolved = io.vidocq.cyrano.internal.gen.ClientProxyRegistry.resolve(subIface);
        var subHandler = new CyranoInvocationHandler(subUri, resolved.specs(), transport, configuration);
        return resolved.instantiator().apply(subHandler);
    }

    /** Placeholder to help usage analysis — removable. */
    @SuppressWarnings("unused")
    private static InvocationHandler unused() { return null; }
}
