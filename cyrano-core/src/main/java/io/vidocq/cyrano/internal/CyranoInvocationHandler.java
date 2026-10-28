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
import org.eclipse.microprofile.rest.client.ext.QueryParamStyle;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
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
    private static final String DISABLE_DEFAULT_MAPPER = "microprofile.rest.client.disable.default.mapper";

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

    /** Spec §8.1 — called by the synthetic close() of the proxy. */
    @Override
    public void markClosed() {
        this.closed = true;
    }

    /**
     * Single entry point called by each method of the generated proxy.
     *
     * @param proxy the proxy instance itself — used to invoke the
     * {@code default} methods referenced by {@code @ClientHeaderParam}
     * @param methodIndex method index in the {@code specs} list
     * @param args arguments passed by the caller
     */
    @Override
    public Object invoke(Object proxy, int methodIndex, Object[] args) throws Exception {
        RequestSpec spec = specs.get(methodIndex);
        return io.vidocq.cyrano.runtime.ProviderInstantiator.current().aroundInvoke(
                proxy,
                spec.method(),
                args,
                () -> invokeInternal(proxy, args, spec));
    }

    private Object invokeInternal(Object proxy, Object[] args, RequestSpec spec) {
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
        seedEntity(reqCtx, r);
        //MP Rest Client §4.2 — standard filter property
        reqCtx.setProperty("org.eclipse.microprofile.rest.client.invokedMethod", spec.method());

        //5. Pipeline ClientRequestFilter (top-up priorities)
        runRequestFilters(reqCtx, spec);

        //SSE — MP Rest Client 4.0 §10: org.reactivestreams.Publisher<T> return
        //type streams the text/event-stream response (Accept already seeded
        //from @Produces). The class is matched by NAME so the reactive-streams
        //jar stays optional at runtime — only SseReturnSupport imports it.
        if (isReactiveStreamsPublisher(spec.returnType())) {
            return ssePublisher(reqCtx, spec);
        }

        //6. Either we've been aborted or we're actually sending the request.
        if (asyncReturn) {
            return invokeAsync(reqCtx, spec);
        }

        CyranoClientResponseContext respCtx = reqCtx.isAborted()
                ? CyranoClientResponseContext.fromAbort(reqCtx.abortResponse())
                : send(reqCtx);

        //7. Pipeline ClientResponseFilter (top-down priorities)
        runResponseFilters(reqCtx, respCtx);

        // 8. Final mapping of the return value
        return mapResponse(respCtx, spec);
    }

    private void runRequestFilters(CyranoClientRequestContext reqCtx, RequestSpec spec) {
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
    }

    private void runResponseFilters(CyranoClientRequestContext reqCtx, CyranoClientResponseContext respCtx) {
        for (var f : configuration.getResponseFilters()) {
            try {
                f.filter(reqCtx, respCtx);
            } catch (IOException ioe) {
                throw new jakarta.ws.rs.ProcessingException(ioe);
            }
        }
    }

    private Object ssePublisher(CyranoClientRequestContext reqCtx, RequestSpec spec) {
        if (reqCtx.isAborted()) {
            int abortStatus = reqCtx.abortResponse() != null
                    ? reqCtx.abortResponse().getStatus() : 200;
            return io.vidocq.cyrano.internal.sse.SseReturnSupport.abortedPublisher(abortStatus);
        }
        HttpRequest sseReq = buildHttpRequest(reqCtx);
        return io.vidocq.cyrano.internal.sse.SseReturnSupport.createPublisher(
                transport, sseReq, innerType(spec.genericReturnType()), configuration);
    }

    private CompletableFuture<Object> invokeAsync(CyranoClientRequestContext reqCtx, RequestSpec spec) {
        Type asyncGenericType = innerType(spec.genericReturnType());
        Class<?> asyncRawType = rawType(asyncGenericType);
        List<AsyncInvocationInterceptor> asyncInterceptors = newAsyncInterceptors();
        prepareContexts(asyncInterceptors);
        //An aborted request is answered asynchronously too: the response filters, readers and
        //applyContext() run off the caller's thread, as for a sent request (BUG-20261008-05).
        if (reqCtx.isAborted()) {
            return CompletableFuture.supplyAsync(() -> {
                applyContexts(asyncInterceptors);
                try {
                    return filterAndMap(reqCtx, CyranoClientResponseContext.fromAbort(reqCtx.abortResponse()),
                            spec, asyncRawType, asyncGenericType);
                } finally {
                    removeContexts(asyncInterceptors);
                }
            }, asyncCallbackExecutor());
        }

        HttpRequest finalReq = buildHttpRequest(reqCtx);
        return transport.sendAsync(finalReq).thenApplyAsync(resp -> {
            applyContexts(asyncInterceptors);
            try {
                return filterAndMap(reqCtx, CyranoClientResponseContext.of(resp),
                        spec, asyncRawType, asyncGenericType);
            } finally {
                removeContexts(asyncInterceptors);
            }
        }, asyncCallbackExecutor());
    }

    private Object filterAndMap(CyranoClientRequestContext reqCtx, CyranoClientResponseContext respCtx,
                                RequestSpec spec, Class<?> rawType, Type genericType) {
        runResponseFilters(reqCtx, respCtx);
        return mapResponse(respCtx, spec, rawType, genericType);
    }

    private static void prepareContexts(List<AsyncInvocationInterceptor> interceptors) {
        for (AsyncInvocationInterceptor interceptor : interceptors) {
            try {
                interceptor.prepareContext();
            } catch (RuntimeException _) {
                //a failing interceptor must not prevent the invocation
            }
        }
    }

    private static void applyContexts(List<AsyncInvocationInterceptor> interceptors) {
        for (AsyncInvocationInterceptor interceptor : interceptors) {
            try {
                interceptor.applyContext();
            } catch (RuntimeException _) {
                //a failing interceptor must not prevent the response processing
            }
        }
    }

    private static void removeContexts(List<AsyncInvocationInterceptor> interceptors) {
        for (AsyncInvocationInterceptor interceptor : interceptors) {
            try {
                interceptor.removeContext();
            } catch (RuntimeException _) {
                //a failing interceptor must not prevent the cleanup of the others
            }
        }
    }

    private CyranoClientResponseContext send(CyranoClientRequestContext reqCtx) {
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
        return CyranoClientResponseContext.of(resp);
    }

    // ============================================================
    // Build the HttpRequest from the final context (post-filters)
    // ============================================================
    private HttpRequest buildHttpRequest(CyranoClientRequestContext ctx) {
        HttpRequest.Builder b = HttpRequest.newBuilder(ctx.getUri());
        if (configuration.getReadTimeoutMs() > 0) {
            b.timeout(Duration.ofMillis(configuration.getReadTimeoutMs()));
        }
        copyHeaders(ctx, b);
        b.method(ctx.getMethod(), bodyPublisher(ctx));
        return b.build();
    }

    private static void copyHeaders(CyranoClientRequestContext ctx, HttpRequest.Builder b) {
        //Headers — prohibited by JDK HttpClient: Host, Connection, etc. Keep
        // everything else, and silently ignore rejections.
        for (var e : ctx.getStringHeaders().entrySet()) {
            for (String v : e.getValue()) {
                try { b.header(e.getKey(), v); }
                catch (IllegalArgumentException _) { /* restricted header */ }
            }
        }
    }

    private HttpRequest.BodyPublisher bodyPublisher(CyranoClientRequestContext ctx) {
        Object entity = ctx.getEntity();
        if (entity == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        if (entity instanceof byte[] bytes) {
            return HttpRequest.BodyPublishers.ofByteArray(bytes);
        }
        //Apply registered MessageBodyWriter (+interceptors) if applicable (spec §4.2 §6.5)
        MessageBodyWriter<?> writer = findMessageBodyWriter(entity.getClass());
        if (writer != null) {
            MediaType mediaType = parseMediaType(ctx.getHeaderString("Content-Type"));
            return HttpRequest.BodyPublishers.ofByteArray(writeEntity(writer, entity, mediaType));
        }
        if (entity instanceof String s) {
            return HttpRequest.BodyPublishers.ofString(s);
        }
        return HttpRequest.BodyPublishers.ofString(jsonbFor(entity.getClass()).toJson(entity));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private byte[] writeEntity(MessageBodyWriter writer, Object entity, MediaType mediaType) {
        List<WriterInterceptor> writerInterceptors = configuration.getWriterInterceptors();
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
            return baos.toByteArray();
        } catch (IOException e) {
            throw new jakarta.ws.rs.ProcessingException(e);
        }
    }

    // ============================================================
    //Step 1: Binding resolution (BeanParam expanded)
    // ============================================================
    /**
     * Binding values resolved for one invocation. A holder filled in place by the per-binding
     * helpers — still one object per call, as the former record was.
     */
    private static final class ResolvedBindings {
        private final Map<String, String> pathParams = new LinkedHashMap<>();
        private final Map<String, List<String>> queryParams = new LinkedHashMap<>();
        private final Map<String, String> matrixParams = new LinkedHashMap<>();
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private final Map<String, String> cookies = new LinkedHashMap<>();
        private final Map<String, String> formParams = new LinkedHashMap<>();
        private Object body;
        private boolean hasBody;

        Map<String, String> pathParams() { return pathParams; }
        Map<String, List<String>> queryParams() { return queryParams; }
        Map<String, String> matrixParams() { return matrixParams; }
        Map<String, List<String>> headers() { return headers; }
        Map<String, String> cookies() { return cookies; }
        Map<String, String> formParams() { return formParams; }
        Object body() { return body; }
        boolean hasBody() { return hasBody; }

        void put(ParamBinding.FieldBinding.Kind kind, String name, String value) {
            switch (kind) {
                case PATH -> pathParams.put(name, value);
                case QUERY -> addMulti(queryParams, name, value);
                case HEADER -> addMulti(headers, name, value);
                case COOKIE -> cookies.put(name, value);
                case FORM -> formParams.put(name, value);
                case MATRIX -> matrixParams.put(name, value);
            }
        }
    }

    private static ResolvedBindings resolveBindings(RequestSpec spec, Object[] args) {
        ResolvedBindings r = new ResolvedBindings();
        for (ParamBinding pb : spec.bindings()) {
            bind(r, pb, args);
        }
        return r;
    }

    private static void bind(ResolvedBindings r, ParamBinding pb, Object[] args) {
        switch (pb) {
            case ParamBinding.Path(var index, var name, var dflt) ->
                    r.pathParams.put(name, valueOrDefault(args[index], dflt));
            case ParamBinding.Query(var index, var name, var dflt) ->
                    bindQuery(r.queryParams, name, args == null ? null : args[index], dflt);
            case ParamBinding.Header(var index, var name, var dflt) ->
                    addMulti(r.headers, name, valueOrDefault(args[index], dflt));
            case ParamBinding.Cookie(var index, var name, var dflt) ->
                    r.cookies.put(name, valueOrDefault(args[index], dflt));
            case ParamBinding.Form(var index, var name, var dflt) ->
                    r.formParams.put(name, valueOrDefault(args[index], dflt));
            case ParamBinding.Matrix(var index, var name, var dflt) ->
                    r.matrixParams.put(name, valueOrDefault(args[index], dflt));
            case ParamBinding.Body(var index) -> {
                r.body = args[index];
                r.hasBody = true;
            }
            case ParamBinding.Bean(var index, var fields) -> bindBean(r, fields, args[index]);
        }
    }

    private static void bindQuery(Map<String, List<String>> queryParams, String name, Object value, String dflt) {
        if (value instanceof java.util.Collection<?> col) {
            for (Object item : col) addMulti(queryParams, name, item == null ? null : stringify(item));
        } else if (value != null && value.getClass().isArray()) {
            for (Object item : (Object[]) value) addMulti(queryParams, name, item == null ? null : stringify(item));
        } else {
            addMulti(queryParams, name, valueOrDefault(value, dflt));
        }
    }

    private static void bindBean(ResolvedBindings r, List<ParamBinding.FieldBinding> fields, Object beanObj) {
        if (beanObj == null) {
            return;
        }
        for (ParamBinding.FieldBinding fb : fields) {
            Object v;
            try { v = fb.field().get(beanObj); }
            catch (IllegalAccessException ex) {
                throw new IllegalStateException(
                        "@BeanParam.field access denied: " + fb.field(), ex);
            }
            String str = valueOrDefault(v, fb.defaultValue());
            if (str != null) {
                r.put(fb.kind(), fb.name(), str);
            }
        }
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
            path = appendMatrixParams(path, r.matrixParams());
        }
        //Query string — spec MP Rest Client §3.4 QueryParamStyle
        String query = r.queryParams().isEmpty() ? "" : queryString(r.queryParams());
        String base = baseUri.toString();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        //If the path is just "/" (explicit @Path method), do not add a slash
        //Final that is not part of the contract (ConfigKeyForMultipleInterfacesTest §6.5).
        if ("/".equals(path) && query.isEmpty()) path = "";
        return buildFinalUri(base + path + query);
    }

    private static String appendMatrixParams(String path, Map<String, String> matrixParams) {
        StringBuilder sb = new StringBuilder(path);
        for (var e : matrixParams.entrySet()) {
            if (e.getValue() != null) {
                sb.append(';').append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                        .append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    private String queryString(Map<String, List<String>> queryParams) {
        var style = configuration.getQueryParamStyle();
        List<String> pairs = new ArrayList<>();
        for (var e : queryParams.entrySet()) {
            List<String> values = e.getValue().stream().filter(v -> v != null).toList();
            if (!values.isEmpty()) {
                addQueryPairs(pairs, style, e.getKey(), values);
            }
        }
        return pairs.isEmpty() ? "" : "?" + String.join("&", pairs);
    }

    private static void addQueryPairs(List<String> pairs, QueryParamStyle style, String rawKey, List<String> values) {
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
        } catch (IllegalArgumentException _) {
            // [] in query string: try the scheme+ssp constructor which preserves raw chars
            int colon = rawUrl.indexOf(':');
            if (colon > 0) {
                try {
                    return new java.net.URI(rawUrl.substring(0, colon), rawUrl.substring(colon + 1), null);
                } catch (java.net.URISyntaxException _) {
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
        addStaticHeaders(headers, spec, runtimeOverrides);
        // @ClientHeaderParam dynamic
        addDynamicHeaders(headers, spec, runtimeOverrides, proxy);
        //Headers installed via RestClientBuilder.header() — spec §3.2, low priority (overridden by @HeaderParam)
        for (var e : configuration.getBuilderHeaders().entrySet()) {
            if (!runtimeOverrides.contains(e.getKey())) {
                headers.add(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        // @HeaderParam runtime
        addRuntimeHeaders(headers, r.headers());
        //@CookieParam → combined "Cookie" header
        if (!r.cookies().isEmpty()) {
            addCookieHeader(headers, r.cookies());
        }

        applyClientHeadersFactory(spec, headers);
    }

    private static void addStaticHeaders(MultivaluedMap<String, Object> headers, RequestSpec spec,
                                         Set<String> runtimeOverrides) {
        for (var e : spec.staticHeaders().entrySet()) {
            if (!runtimeOverrides.contains(e.getKey())) {
                for (String v : e.getValue()) headers.add(e.getKey(), v);
            }
        }
    }

    private static void addDynamicHeaders(MultivaluedMap<String, Object> headers, RequestSpec spec,
                                          Set<String> runtimeOverrides, Object proxy) {
        for (var e : spec.dynamicHeaders().entrySet()) {
            if (!runtimeOverrides.contains(e.getKey())) {
                String value = dynamicHeaderValue(proxy, spec, e.getKey(), e.getValue());
                if (value != null) headers.add(e.getKey(), value);
            }
        }
    }

    /** Value of a computed header, or {@code null} when it is absent or its optional compute method failed. */
    private static String dynamicHeaderValue(Object proxy, RequestSpec spec, String headerName,
                                             RequestSpec.DynamicHeader dh) {
        try {
            return invokeHeaderMethod(proxy, spec.method().getDeclaringClass(), dh.methodName(), headerName);
        } catch (RuntimeException ex) {
            if (dh.required()) throw ex;
            return null;
        }
    }

    private static void addRuntimeHeaders(MultivaluedMap<String, Object> headers, Map<String, List<String>> runtime) {
        for (var e : runtime.entrySet()) {
            for (String v : e.getValue()) {
                if (v != null) headers.add(e.getKey(), v);
            }
        }
    }

    private static void addCookieHeader(MultivaluedMap<String, Object> headers, Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (var e : cookies.entrySet()) {
            if (e.getValue() != null) {
                if (!sb.isEmpty()) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        if (!sb.isEmpty()) headers.putSingle("Cookie", sb.toString());
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
    private static void seedEntity(CyranoClientRequestContext ctx, ResolvedBindings r) {
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
        Object target = Modifier.isStatic(dm.getModifiers()) ? null : proxy;
        Object[] callArgs = dm.getParameterCount() == 0 ? new Object[0] : new Object[]{headerName};
        Object result;
        try {
            result = dm.invoke(target, callArgs);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            throw headerMethodFailure(ite, methodName);
        } catch (IllegalAccessException iae) {
            throw new IllegalStateException("Access denied to @ClientHeaderParam("
                    + methodName + ") sur " + iface.getName(), iae);
        }
        if (result instanceof String[] arr) {
            return String.join(",", arr);
        }
        return result == null ? null : result.toString();
    }

    /**
     * Spec §6.5: the caller must see the exception of a compute method as-is (or the header is
     * silently omitted if required=false — decided upstream by dynamicHeaderValue).
     */
    private static RuntimeException headerMethodFailure(java.lang.reflect.InvocationTargetException ite,
                                                        String methodName) {
        Throwable cause = ite.getCause();
        if (cause instanceof RuntimeException re) return re;
        if (cause instanceof Error err) throw err;
        return new IllegalStateException("@ClientHeaderParam(" + methodName + ") failed",
                cause != null ? cause : ite);
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
                return findStaticHeaderMethod(ext, mname);
            } catch (ClassNotFoundException _) {
                // fall back to the local lookup below
            }
        }
        return findLocalHeaderMethod(iface, name);
    }

    private static Method findStaticHeaderMethod(Class<?> ext, String methodName) {
        Method bestMatch = null;
        for (Method m : ext.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getName().equals(methodName)) {
                int pc = m.getParameterCount();
                if (pc == 0) return m;
                if (pc == 1 && m.getParameterTypes()[0] == String.class) bestMatch = m;
            }
        }
        return bestMatch;
    }

    private static Method findLocalHeaderMethod(Class<?> iface, String name) {
        for (Method m : iface.getMethods()) {
            if (m.getName().equals(name) && isHeaderMethodSignature(m)) return m;
        }
        //search in declared methods (edge cases)
        for (Method m : iface.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    private static boolean isHeaderMethodSignature(Method m) {
        int pc = m.getParameterCount();
        return pc == 0 || (pc == 1 && m.getParameterTypes()[0] == String.class);
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
        Optional<RuntimeException> userEx = applyExceptionMappers(respCtx, body);
        if (userEx.isPresent()) throw userEx.get();

        if (status >= 400) {
            return mapErrorResponse(respCtx, body, rt, status);
        }

        if (rt == void.class || rt == Void.class) return null;
        //Response return type — pass configuration so readEntity() can use registered readers
        if (rt == Response.class) return CyranoLightResponse.of(respCtx, body, configuration);

        //MessageBodyReader (+interceptors) — check registered readers before the default path
        MessageBodyReader<?> reader = findMessageBodyReader(rt);
        if (reader != null && body != null) {
            return readEntity(reader, respCtx, body, rt, genericType);
        }
        return mapDefaultEntity(body, rt, genericType);
    }

    private Object mapErrorResponse(CyranoClientResponseContext respCtx, String body, Class<?> rt, int status) {
        if (rt == Optional.class && status == 404) {
            return Optional.empty();
        }
        //spec §8: microprofile.rest.client.disable.default.mapper=true → return raw response
        if (!isDefaultMapperDisabled()) {
            throw defaultException(respCtx, body);
        }
        if (rt == void.class || rt == Void.class) return null;
        return CyranoLightResponse.of(respCtx, body, configuration);
    }

    private boolean isDefaultMapperDisabled() {
        Object disableProp = configuration.getProperty(DISABLE_DEFAULT_MAPPER);
        if (disableProp == null) {
            disableProp = System.getProperty(DISABLE_DEFAULT_MAPPER);
        }
        return Boolean.TRUE.equals(disableProp)
                || "true".equalsIgnoreCase(String.valueOf(disableProp));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object readEntity(MessageBodyReader reader, CyranoClientResponseContext respCtx, String body,
                              Class<?> rt, Type genericType) {
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

    /** Return types mapped without a registered reader: text, primitives, JSON-B containers and POJOs. */
    private Object mapDefaultEntity(String body, Class<?> rt, Type genericType) {
        if (rt == String.class) return body;
        if (rt.isPrimitive() || Number.class.isAssignableFrom(rt)
                || rt == Boolean.class || rt == Character.class) {
            return parsePrimitive(body, rt);
        }
        if (rt == Optional.class) {
            return Optional.ofNullable(readJson(body, innerType(genericType)));
        }
        if (rt == Set.class) {
            Object asList = readJson(body, listOf(innerType(genericType)));
            return new LinkedHashSet<>((List<?>) asList);
        }
        // List, Collection, Map and POJO
        return readJson(body, genericType);
    }

    private Optional<RuntimeException> applyExceptionMappers(CyranoClientResponseContext respCtx,
                                                             String body) {
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

    /**
     * SSE detection (spec §10) — by NAME, not by {@code Class} reference:
     * cyrano-core must not hard-require the reactive-streams jar
     * ({@code requires static org.reactivestreams} in module-info).
     */
    private static boolean isReactiveStreamsPublisher(Class<?> returnType) {
        return "org.reactivestreams.Publisher".equals(returnType.getName());
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
                } catch (RuntimeException _) {
                    //failed factory ignored
                }
            }
        }
        return out;
    }

    /**
     * Where the response of an asynchronous method is processed: the builder's executor service, or else a new
     * virtual thread per response. Never a direct executor: {@code thenApplyAsync(fn, Runnable::run)} runs
     * {@code fn} on the caller's thread when the response is already complete (BUG-20261008-05).
     */
    private Executor asyncCallbackExecutor() {
        Executor configured = configuration.getExecutorService();
        if (configured != null) {
            return configured;
        }
        return ASYNC_CALLBACKS;
    }

    private static final Executor ASYNC_CALLBACKS = task -> Thread.ofVirtual().name("cyrano-async").start(task);

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
        jakarta.json.JsonObject json = readJsonObjectOrNull(body);
        if (json == null) return;
        Class<?> cursor = rawType;
        while (cursor != null && cursor != Object.class) {
            for (var field : cursor.getDeclaredFields()) {
                if (isPatchable(field)) {
                    patchField(target, cursor, field, json);
                }
            }
            cursor = cursor.getSuperclass();
        }
    }

    private static jakarta.json.JsonObject readJsonObjectOrNull(String body) {
        try (var reader = jakarta.json.Json.createReader(new java.io.StringReader(body))) {
            return reader.readObject();
        } catch (RuntimeException _) {
            return null; //not a JSON object: nothing to patch
        }
    }

    private static boolean isPatchable(Field field) {
        return !Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())
                && field.getAnnotation(JsonbTransient.class) == null;
    }

    private static void patchField(Object target, Class<?> owner, Field field, jakarta.json.JsonObject json) {
        String jsonName = jsonNameOf(field);
        if (!json.containsKey(jsonName)) return;
        Object value = jsonValue(json.get(jsonName), field.getType());
        if (value == null) return;
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            VarHandle handle = lookup.findVarHandle(owner, field.getName(), field.getType());
            handle.set(target, value);
        } catch (RuntimeException | ReflectiveOperationException _) {
            //best-effort fallback only
        }
    }

    private static String jsonNameOf(Field field) {
        JsonbProperty named = field.getAnnotation(JsonbProperty.class);
        if (named != null && named.value() != null && !named.value().isBlank()) {
            return named.value();
        }
        return field.getName();
    }

    private static Object jsonValue(jakarta.json.JsonValue value, Class<?> targetType) {
        if (value == null || value == jakarta.json.JsonValue.NULL) return null;
        if (targetType == String.class) {
            return jsonString(value);
        }
        if (targetType == int.class || targetType == Integer.class) {
            return jsonInt(value);
        }
        if (targetType == long.class || targetType == Long.class) {
            return jsonLong(value);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return value == jakarta.json.JsonValue.TRUE
                    || "true".equalsIgnoreCase(value.toString());
        }
        return null;
    }

    private static Object jsonString(jakarta.json.JsonValue value) {
        if (value.getValueType() == jakarta.json.JsonValue.ValueType.STRING) {
            return ((jakarta.json.JsonString) value).getString();
        }
        return value.toString();
    }

    private static Object jsonInt(jakarta.json.JsonValue value) {
        if (value.getValueType() == jakarta.json.JsonValue.ValueType.NUMBER) {
            return ((jakarta.json.JsonNumber) value).intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private static Object jsonLong(jakarta.json.JsonValue value) {
        if (value.getValueType() == jakarta.json.JsonValue.ValueType.NUMBER) {
            return ((jakarta.json.JsonNumber) value).longValue();
        }
        return Long.parseLong(value.toString());
    }

    @SuppressWarnings("unchecked")
    private Jsonb jsonbFor(Class<?> targetType) {
        boolean debug = Boolean.getBoolean("cyrano.debug.jsonb");
        for (Object inst : configuration.getInstances()) {
            if (inst instanceof ContextResolver<?> resolver) {
                Object resolved;
                try {
                    resolved = ((ContextResolver<Object>) resolver).getContext(targetType);
                } catch (RuntimeException _) {
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
        return switch (type.getName()) {
            case "int", "java.lang.Integer" -> Integer.parseInt(s);
            case "long", "java.lang.Long" -> Long.parseLong(s);
            case "short", "java.lang.Short" -> Short.parseShort(s);
            case "byte", "java.lang.Byte" -> Byte.parseByte(s);
            case "double", "java.lang.Double" -> Double.parseDouble(s);
            case "float", "java.lang.Float" -> Float.parseFloat(s);
            case "boolean", "java.lang.Boolean" -> Boolean.parseBoolean(s);
            case "char", "java.lang.Character" -> s.isEmpty() ? '\0' : s.charAt(0);
            default -> throw new UnsupportedOperationException("Unsupported primitive: " + type);
        };
    }

    /** Find the first registered MessageBodyReader applicable to the given type. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private MessageBodyReader<?> findMessageBodyReader(Class<?> type) {
        MediaType wildcard = MediaType.WILDCARD_TYPE;
        for (Object inst : configuration.getInstances()) {
            if (inst instanceof MessageBodyReader r && r.isReadable(type, type, new Annotation[0], wildcard)) {
                return r;
            }
        }
        return null;
    }

    /** Find the first registered MessageBodyWriter applicable to the given type. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private MessageBodyWriter<?> findMessageBodyWriter(Class<?> type) {
        MediaType wildcard = MediaType.WILDCARD_TYPE;
        for (Object inst : configuration.getInstances()) {
            if (inst instanceof MessageBodyWriter w && w.isWriteable(type, type, new Annotation[0], wildcard)) {
                return w;
            }
        }
        return null;
    }

    private static MediaType parseMediaType(String ct) {
        if (ct == null || ct.isBlank()) return MediaType.APPLICATION_JSON_TYPE;
        try { return MediaType.valueOf(ct); }
        catch (IllegalArgumentException _) { return MediaType.APPLICATION_JSON_TYPE; }
    }

    private static MediaType extractResponseMediaType(CyranoClientResponseContext respCtx) {
        //Header names are case-insensitive (RFC 9110 §5.1): servers send Content-type or content-type too.
        String ct = respCtx.getHeaderString(jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE);
        if (ct == null || ct.isEmpty()) return MediaType.WILDCARD_TYPE;
        return parseMediaType(ct);
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
