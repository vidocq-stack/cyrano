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
package io.vidocq.cyrano.processor;

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

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates {@code <Interface>$$CyranoClient} Java sources at compile time for every
 * {@code @RegisterRestClient} interface, plus the sub-resource interfaces reachable
 * through locators in the same module (APT-first rule, codegen audit CG-01).
 *
 * <p>The generated class mirrors the hand-written reference template
 * ({@code FixtureApi$$CyranoClient} in cyrano-core tests): it implements the client
 * interface and {@code java.io.Closeable}, embeds a literal
 * {@code spi.gen.ClientDescriptor} replicating exactly what
 * {@code CyranoInterfaceScanner} would derive at runtime, dispatches every method to
 * {@code spi.gen.ClientInvoker} by index, and exposes a nested ServiceLoader-able
 * {@code Factory}.</p>
 *
 * <p><strong>Safety valve</strong>: any construct this generator cannot emit
 * faithfully — including every definition the runtime scanner would <em>reject</em>
 * (the spec mandates a {@code RestClientDefinitionException} at
 * {@code RestClientBuilder.build()} time, not a compile error) — produces a compiler
 * NOTE and skips the interface. Correctness never depends on the processor: the
 * runtime Class-File fallback always preserves exact spec behaviour.</p>
 */
public final class CyranoClientProcessor extends AbstractProcessor {

    private static final String REGISTER_REST_CLIENT =
            "org.eclipse.microprofile.rest.client.inject.RegisterRestClient";
    private static final String SUFFIX = "$$CyranoClient";
    private static final Pattern TEMPLATE_PARAM_PATTERN = Pattern.compile("\\{([^}/]+)}");
    /** Closing brace of a member of the generated class. */
    private static final String MEMBER_END = "    }\n";
    /** {@code @Override} on a method of the generated nested {@code Factory}. */
    private static final String NESTED_OVERRIDE = "        @java.lang.Override\n";
    /** Indentation of a {@code ClientMethodDescriptor} constructor argument in the generated descriptor. */
    private static final String ARG_INDENT = "                            ";

    private Elements elements;
    private Types types;
    private Filer filer;
    private Messager messager;

    private final Set<String> generatedFqns = new LinkedHashSet<>();
    private final Set<String> factoryFqns = new TreeSet<>();
    private boolean servicesWritten;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(REGISTER_REST_CLIENT);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(javax.annotation.processing.ProcessingEnvironment env) {
        super.init(env);
        this.elements = env.getElementUtils();
        this.types = env.getTypeUtils();
        this.filer = env.getFiler();
        this.messager = env.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        TypeElement marker = elements.getTypeElement(REGISTER_REST_CLIENT);
        if (marker != null) {
            Deque<TypeElement> queue = new ArrayDeque<>();
            for (Element e : roundEnv.getElementsAnnotatedWith(marker)) {
                if (e.getKind() == ElementKind.INTERFACE) {
                    queue.add((TypeElement) e);
                }
            }
            while (!queue.isEmpty()) {
                TypeElement iface = queue.poll();
                if (generatedFqns.add(elements.getBinaryName(iface).toString())) {
                    generateQueued(iface, queue);
                }
            }
        }
        if (roundEnv.processingOver()) {
            writeServicesFile();
        }
        return false;
    }

    /** Generates the client of {@code iface} and queues its same-module sub-resource interfaces. */
    private void generateQueued(TypeElement iface, Deque<TypeElement> queue) {
        try {
            List<TypeElement> subResources = generateClient(iface);
            for (TypeElement sub : subResources) {
                if (sameModule(iface, sub)) {
                    queue.add(sub);
                } else {
                    note(sub, "sub-resource interface lives in another module; "
                            + "its proxy will use the runtime fallback");
                }
            }
        } catch (SkipGeneration skip) {
            note(iface, "skipping " + iface.getQualifiedName() + SUFFIX + " — "
                    + skip.getMessage() + " (runtime fallback will handle this interface)");
        } catch (IOException | RuntimeException e) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                    "Cyrano client generation failed, runtime fallback will handle it: " + e, iface);
        }
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    /** Marker for the documented skip-on-complexity valve. */
    private static final class SkipGeneration extends Exception {
        SkipGeneration(String message) {
            super(message);
        }
    }

    private record HeaderModel(Map<String, List<String>> statics,
                               Map<String, DynamicModel> dynamics) {
        HeaderModel copy() {
            return new HeaderModel(new LinkedHashMap<>(statics), new LinkedHashMap<>(dynamics));
        }
    }

    private record DynamicModel(String methodName, boolean required) {
    }

    private sealed interface ParamModel {
        record Simple(String kind, int index, String name, String defaultValue) implements ParamModel {}
        record Body(int index) implements ParamModel {}
        record Bean(int index, TypeMirror beanType, List<BeanFieldModel> fields) implements ParamModel {}
    }

    private record BeanFieldModel(String fieldName, String kind, String name, String defaultValue) {
    }

    private record MethodModel(ExecutableElement element,
                               ExecutableType resolved,
                               String httpMethod,
                               String template,
                               List<ParamModel> params,
                               List<String> consumes,
                               List<String> produces,
                               HeaderModel headers,
                               TypeElement subResource) {
    }

    /**
     * Generates the client for {@code iface}; returns the sub-resource interfaces it
     * references through locators (candidates for transitive generation).
     */
    private List<TypeElement> generateClient(TypeElement iface) throws SkipGeneration, IOException {
        if (!iface.getTypeParameters().isEmpty()) {
            throw new SkipGeneration("generic client interfaces are not emitted as source");
        }
        String basePath = normalize(pathOf(iface));
        HeaderModel typeHeaders = collectHeaders(iface, iface, new HeaderModel(new LinkedHashMap<>(), new LinkedHashMap<>()));
        List<String> typeConsumes = mediaTypes(iface.getAnnotation(Consumes.class));
        List<String> typeProduces = mediaTypes(iface.getAnnotation(Produces.class));

        List<MethodModel> models = new ArrayList<>();
        List<TypeElement> subResources = new ArrayList<>();
        for (ExecutableElement method : abstractMethods(iface)) {
            MethodModel model = buildMethod(iface, method, basePath, typeHeaders, typeConsumes, typeProduces);
            if (model == null) {
                continue; // synthetic close(), handled by the generated close()
            }
            models.add(model);
            if (model.subResource() != null
                    && !generatedFqns.contains(elements.getBinaryName(model.subResource()).toString())) {
                subResources.add(model.subResource());
            }
        }
        if (models.stream().noneMatch(m -> m.httpMethod() != null)) {
            throw new SkipGeneration("no method annotated with an HTTP verb (spec §3 requires at least one)");
        }
        emit(iface, models);
        return subResources;
    }

    /** Abstract interface methods, Object members and static/default methods excluded. */
    private List<ExecutableElement> abstractMethods(TypeElement iface) {
        List<ExecutableElement> out = new ArrayList<>();
        for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(iface))) {
            if (method.getEnclosingElement().getKind() == ElementKind.INTERFACE
                    && method.getModifiers().contains(Modifier.ABSTRACT)) {
                out.add(method);
            }
        }
        return out;
    }

    private MethodModel buildMethod(TypeElement iface, ExecutableElement method, String basePath,
                                    HeaderModel typeHeaders, List<String> typeConsumes,
                                    List<String> typeProduces) throws SkipGeneration {
        if (!method.getTypeParameters().isEmpty()) {
            throw new SkipGeneration("generic method " + method.getSimpleName());
        }
        ExecutableType resolved = (ExecutableType) types.asMemberOf((DeclaredType) iface.asType(), method);
        String verb = httpVerbOf(iface, method);
        String template = joinPath(basePath, normalize(pathOf(method)));
        List<ParamModel> params = paramModels(method);
        HeaderModel headers = collectHeaders(iface, method, typeHeaders.copy());
        List<String> consumes = method.getAnnotation(Consumes.class) != null
                ? mediaTypes(method.getAnnotation(Consumes.class)) : typeConsumes;
        List<String> produces = method.getAnnotation(Produces.class) != null
                ? mediaTypes(method.getAnnotation(Produces.class)) : typeProduces;

        if (verb == null) {
            TypeElement sub = subResourceInterface(method, resolved);
            if (sub != null) {
                return new MethodModel(method, resolved, null, template, params,
                        consumes, produces, headers, sub);
            }
            if (isSyntheticCloseCandidate(method)) {
                return null; // covered by the generated close() delegating to markClosed()
            }
            // The runtime scanner silently skips such methods; a source proxy cannot.
            throw new SkipGeneration("abstract method " + method.getSimpleName()
                    + " has no HTTP verb and is not a sub-resource locator");
        }
        if (isSyntheticCloseCandidate(method)) {
            throw new SkipGeneration("method close() collides with the generated Closeable contract");
        }
        validatePathBindings(method, template, params);
        return new MethodModel(method, resolved, verb, template, params,
                consumes, produces, headers, null);
    }

    private boolean isSyntheticCloseCandidate(ExecutableElement method) {
        return method.getSimpleName().contentEquals("close") && method.getParameters().isEmpty();
    }

    private String httpVerbOf(TypeElement iface, ExecutableElement method) throws SkipGeneration {
        String verb = null;
        for (AnnotationMirror mirror : method.getAnnotationMirrors()) {
            HttpMethod meta = mirror.getAnnotationType().asElement().getAnnotation(HttpMethod.class);
            if (meta != null) {
                if (verb != null) {
                    throw new SkipGeneration("multiple HTTP verb annotations on "
                            + iface.getQualifiedName() + "#" + method.getSimpleName());
                }
                verb = meta.value();
            }
        }
        return verb;
    }

    private TypeElement subResourceInterface(ExecutableElement method, ExecutableType resolved) {
        if (method.getAnnotation(Path.class) == null) return null;
        TypeMirror ret = resolved.getReturnType();
        if (ret.getKind() != TypeKind.DECLARED) return null;
        Element element = ((DeclaredType) ret).asElement();
        return element.getKind() == ElementKind.INTERFACE ? (TypeElement) element : null;
    }

    private List<ParamModel> paramModels(ExecutableElement method) throws SkipGeneration {
        List<ParamModel> out = new ArrayList<>();
        List<? extends VariableElement> parameters = method.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            out.add(paramModel(parameters.get(i), i));
        }
        return out;
    }

    private ParamModel paramModel(VariableElement p, int i) throws SkipGeneration {
        String dflt = p.getAnnotation(DefaultValue.class) != null
                ? p.getAnnotation(DefaultValue.class).value() : null;
        PathParam path = p.getAnnotation(PathParam.class);
        if (path != null) return new ParamModel.Simple("Path", i, path.value(), dflt);
        QueryParam query = p.getAnnotation(QueryParam.class);
        if (query != null) return new ParamModel.Simple("Query", i, query.value(), dflt);
        HeaderParam header = p.getAnnotation(HeaderParam.class);
        if (header != null) return new ParamModel.Simple("Header", i, header.value(), dflt);
        CookieParam cookie = p.getAnnotation(CookieParam.class);
        if (cookie != null) return new ParamModel.Simple("Cookie", i, cookie.value(), dflt);
        FormParam form = p.getAnnotation(FormParam.class);
        if (form != null) return new ParamModel.Simple("Form", i, form.value(), dflt);
        MatrixParam matrix = p.getAnnotation(MatrixParam.class);
        if (matrix != null) return new ParamModel.Simple("Matrix", i, matrix.value(), dflt);
        if (p.getAnnotation(BeanParam.class) != null) {
            return beanModel(i, p);
        }
        return new ParamModel.Body(i);
    }

    private ParamModel beanModel(int index, VariableElement param) throws SkipGeneration {
        TypeMirror beanType = param.asType();
        if (beanType.getKind() != TypeKind.DECLARED) {
            throw new SkipGeneration("@BeanParam on non-declared type " + beanType);
        }
        List<BeanFieldModel> fields = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        TypeElement c = (TypeElement) ((DeclaredType) beanType).asElement();
        // Same hierarchy walk and shadowing rule as CyranoInterfaceScanner#extractFieldBindings.
        while (c != null && !c.getQualifiedName().contentEquals("java.lang.Object")) {
            for (VariableElement f : ElementFilter.fieldsIn(c.getEnclosedElements())) {
                if (!f.getModifiers().contains(Modifier.STATIC) && seen.add(f.getSimpleName().toString())) {
                    BeanFieldModel field = fieldModel(f);
                    if (field != null) fields.add(field);
                }
            }
            TypeMirror superType = c.getSuperclass();
            c = superType.getKind() == TypeKind.DECLARED
                    ? (TypeElement) ((DeclaredType) superType).asElement() : null;
        }
        return new ParamModel.Bean(index, beanType, fields);
    }

    /**
     * Model of a {@code @BeanParam} field, or {@code null} when it carries no parameter annotation.
     * When a field carries several, the last one of PATH, QUERY, HEADER, COOKIE, FORM, MATRIX wins.
     */
    private static BeanFieldModel fieldModel(VariableElement f) {
        String dflt = f.getAnnotation(DefaultValue.class) != null
                ? f.getAnnotation(DefaultValue.class).value() : null;
        String fieldName = f.getSimpleName().toString();
        MatrixParam fm = f.getAnnotation(MatrixParam.class);
        if (fm != null) return new BeanFieldModel(fieldName, "MATRIX", fm.value(), dflt);
        FormParam ff = f.getAnnotation(FormParam.class);
        if (ff != null) return new BeanFieldModel(fieldName, "FORM", ff.value(), dflt);
        CookieParam fc = f.getAnnotation(CookieParam.class);
        if (fc != null) return new BeanFieldModel(fieldName, "COOKIE", fc.value(), dflt);
        HeaderParam fh = f.getAnnotation(HeaderParam.class);
        if (fh != null) return new BeanFieldModel(fieldName, "HEADER", fh.value(), dflt);
        QueryParam fq = f.getAnnotation(QueryParam.class);
        if (fq != null) return new BeanFieldModel(fieldName, "QUERY", fq.value(), dflt);
        PathParam fp = f.getAnnotation(PathParam.class);
        if (fp != null) return new BeanFieldModel(fieldName, "PATH", fp.value(), dflt);
        return null;
    }

    /**
     * Mirrors the scanner's {@code validateClientHeaderParams} + {@code collectClientHeaders}:
     * any definition the runtime would reject becomes a skip, so the spec-mandated
     * {@code RestClientDefinitionException} still surfaces from the fallback path.
     */
    private HeaderModel collectHeaders(TypeElement iface, Element target, HeaderModel into)
            throws SkipGeneration {
        // getAnnotationsByType already looks through the @ClientHeaderParams container.
        List<ClientHeaderParam> all = List.of(target.getAnnotationsByType(ClientHeaderParam.class));
        Set<String> seenNames = new LinkedHashSet<>();
        for (ClientHeaderParam h : all) {
            if (!seenNames.add(h.name())) {
                throw new SkipGeneration("header '" + h.name() + "' declared multiple times on "
                        + iface.getQualifiedName());
            }
            validateComputeReference(iface, h);
        }
        for (ClientHeaderParam h : all) {
            String[] values = h.value();
            if (values.length == 1 && isComputeExpression(values[0])) {
                String methodName = values[0].substring(1, values[0].length() - 1);
                into.dynamics().put(h.name(), new DynamicModel(methodName, h.required()));
                into.statics().remove(h.name());
            } else {
                into.statics().put(h.name(), List.of(values));
                into.dynamics().remove(h.name());
            }
        }
        return into;
    }

    private void validateComputeReference(TypeElement iface, ClientHeaderParam h) throws SkipGeneration {
        boolean hasCompute = false;
        String computeRef = null;
        for (String value : h.value()) {
            if (isComputeExpression(value)) {
                hasCompute = true;
                computeRef = value.substring(1, value.length() - 1);
            }
        }
        if (!hasCompute) return;
        if (h.value().length != 1) {
            throw new SkipGeneration("@ClientHeaderParam('" + h.name()
                    + "') mixes a compute method with other values");
        }
        if (!computeMethodExists(iface, computeRef)) {
            throw new SkipGeneration("invalid or missing compute method for @ClientHeaderParam('"
                    + h.name() + "'): " + computeRef);
        }
    }

    private boolean computeMethodExists(TypeElement iface, String computeRef) {
        int lastDot = computeRef.lastIndexOf('.');
        if (lastDot > 0) {
            TypeElement ext = elements.getTypeElement(computeRef.substring(0, lastDot));
            if (ext == null) return false;
            String methodName = computeRef.substring(lastDot + 1);
            for (ExecutableElement m : ElementFilter.methodsIn(elements.getAllMembers(ext))) {
                if (m.getModifiers().contains(Modifier.STATIC) && m.getSimpleName().contentEquals(methodName)
                        && isValidComputeSignature(m)) {
                    return true;
                }
            }
            return false;
        }
        for (ExecutableElement m : ElementFilter.methodsIn(elements.getAllMembers(iface))) {
            if (m.getSimpleName().contentEquals(computeRef) && isValidComputeSignature(m)) {
                return true;
            }
        }
        return false;
    }

    private boolean isValidComputeSignature(ExecutableElement m) {
        TypeMirror ret = m.getReturnType();
        boolean stringReturn = isString(ret)
                || (ret.getKind() == TypeKind.ARRAY
                        && isString(((javax.lang.model.type.ArrayType) ret).getComponentType()));
        if (!stringReturn) return false;
        if (m.getParameters().isEmpty()) return true;
        return m.getParameters().size() == 1 && isString(m.getParameters().get(0).asType());
    }

    private boolean isString(TypeMirror t) {
        return t.getKind() == TypeKind.DECLARED
                && ((TypeElement) ((DeclaredType) t).asElement()).getQualifiedName()
                        .contentEquals("java.lang.String");
    }

    private static boolean isComputeExpression(String value) {
        return value != null && value.startsWith("{") && value.endsWith("}") && value.length() > 2;
    }

    private void validatePathBindings(ExecutableElement method, String template,
                                      List<ParamModel> params) throws SkipGeneration {
        Set<String> placeholders = new LinkedHashSet<>();
        Matcher matcher = TEMPLATE_PARAM_PATTERN.matcher(template);
        while (matcher.find()) placeholders.add(matcher.group(1));

        Set<String> bound = boundPathNames(params);
        for (String b : bound) {
            if (!placeholders.contains(b)) {
                throw new SkipGeneration("@PathParam('" + b + "') has no placeholder in '" + template
                        + "' (" + method.getSimpleName() + ")");
            }
        }
        for (String ph : placeholders) {
            if (!bound.contains(ph)) {
                throw new SkipGeneration("placeholder '{" + ph + "}' has no @PathParam ("
                        + method.getSimpleName() + ")");
            }
        }
    }

    /** Names bound to a path placeholder, by {@code @PathParam} parameters or {@code @BeanParam} fields. */
    private static Set<String> boundPathNames(List<ParamModel> params) {
        Set<String> bound = new LinkedHashSet<>();
        for (ParamModel p : params) {
            if (p instanceof ParamModel.Simple(var kind, _, var name, _) && kind.equals("Path")) {
                bound.add(name);
            } else if (p instanceof ParamModel.Bean(_, _, var fields)) {
                for (BeanFieldModel f : fields) {
                    if (f.kind().equals("PATH")) bound.add(f.name());
                }
            }
        }
        return bound;
    }

    private boolean sameModule(TypeElement a, TypeElement b) {
        return elements.getModuleOf(a).equals(elements.getModuleOf(b));
    }

    // ------------------------------------------------------------------
    // Emission
    // ------------------------------------------------------------------

    private void emit(TypeElement iface, List<MethodModel> models) throws IOException, SkipGeneration {
        String binaryName = elements.getBinaryName(iface).toString();
        String pkg = elements.getPackageOf(iface).getQualifiedName().toString();
        String simpleBinary = pkg.isEmpty() ? binaryName : binaryName.substring(pkg.length() + 1);
        String className = simpleBinary + SUFFIX;
        String generatedFqn = (pkg.isEmpty() ? "" : pkg + ".") + className;
        String ifaceSource = iface.getQualifiedName().toString();

        StringBuilder out = new StringBuilder(8192);
        if (!pkg.isEmpty()) {
            out.append("package ").append(pkg).append(";\n\n");
        }
        out.append("// Generated by io.vidocq.cyrano.processor.CyranoClientProcessor — do not edit.\n");
        out.append("@java.lang.SuppressWarnings({\"unchecked\", \"cast\"})\n");
        out.append("public final class ").append(className)
                .append(" implements ").append(ifaceSource).append(", java.io.Closeable {\n\n");

        emitDescriptor(out, ifaceSource, models);

        out.append("    private final io.vidocq.cyrano.spi.gen.ClientInvoker invoker;\n\n");
        out.append("    public ").append(className)
                .append("(io.vidocq.cyrano.spi.gen.ClientInvoker invoker) {\n")
                .append("        this.invoker = invoker;\n")
                .append(MEMBER_END);

        for (int i = 0; i < models.size(); i++) {
            emitMethod(out, models.get(i), i);
        }

        out.append("\n    @java.lang.Override\n")
                .append("    public void close() {\n")
                .append("        this.invoker.markClosed();\n")
                .append(MEMBER_END);

        out.append("\n    /** ServiceLoader-able factory — see io.vidocq.cyrano.spi.gen.ClientProxyFactory. */\n")
                .append("    public static final class Factory implements io.vidocq.cyrano.spi.gen.ClientProxyFactory {\n\n")
                .append(NESTED_OVERRIDE)
                .append("        public java.lang.Class<?> clientInterface() {\n")
                .append("            return ").append(ifaceSource).append(".class;\n")
                .append("        }\n\n")
                .append(NESTED_OVERRIDE)
                .append("        public io.vidocq.cyrano.spi.gen.ClientDescriptor descriptor() {\n")
                .append("            return DESCRIPTOR;\n")
                .append("        }\n\n")
                .append(NESTED_OVERRIDE)
                .append("        public java.lang.Object newProxy(io.vidocq.cyrano.spi.gen.ClientInvoker invoker) {\n")
                .append("            return new ").append(className).append("(invoker);\n")
                .append("        }\n")
                .append(MEMBER_END);

        out.append("}\n");

        try {
            var file = filer.createSourceFile(generatedFqn, iface);
            try (Writer w = file.openWriter()) {
                w.write(out.toString());
            }
        } catch (FilerException e) {
            // Already generated in a previous round / incremental rebuild — keep the first one.
            note(iface, "skipping duplicate generation of " + generatedFqn + ": " + e.getMessage());
            return;
        }
        factoryFqns.add(generatedFqn + "$Factory");
    }

    private void emitDescriptor(StringBuilder out, String ifaceSource, List<MethodModel> models)
            throws SkipGeneration {
        out.append("    private static final io.vidocq.cyrano.spi.gen.ClientDescriptor DESCRIPTOR =\n")
                .append("            new io.vidocq.cyrano.spi.gen.ClientDescriptor(")
                .append(ifaceSource).append(".class, java.util.List.of(\n");
        for (int i = 0; i < models.size(); i++) {
            MethodModel m = models.get(i);
            out.append("                    new io.vidocq.cyrano.spi.gen.ClientMethodDescriptor(\n");
            out.append(ARG_INDENT).append(quote(m.element().getSimpleName().toString()))
                    .append(",\n");
            out.append("                            java.util.List.of(");
            // The JVM signature the runtime looks the method up by: the erasure of the declared parameter types, not of
            // the types the client interface binds them to (post(T) inherited as post(String) is still post(Object)).
            List<? extends TypeMirror> paramTypes = ((ExecutableType) m.element().asType()).getParameterTypes();
            for (int p = 0; p < paramTypes.size(); p++) {
                if (p > 0) out.append(", ");
                out.append(erasedClassLiteral(paramTypes.get(p)));
            }
            out.append("),\n");
            out.append(ARG_INDENT)
                    .append(m.httpMethod() == null ? "null" : quote(m.httpMethod())).append(",\n");
            out.append(ARG_INDENT).append(quote(m.template())).append(",\n");
            out.append("                            java.util.List.of(");
            for (int p = 0; p < m.params().size(); p++) {
                if (p > 0) out.append(",\n                                    ");
                emitParam(out, m.params().get(p));
            }
            out.append("),\n");
            out.append(ARG_INDENT).append(stringList(m.consumes())).append(",\n");
            out.append(ARG_INDENT).append(stringList(m.produces())).append(",\n");
            out.append(ARG_INDENT).append(staticHeadersMap(m.headers().statics())).append(",\n");
            out.append(ARG_INDENT).append(dynamicHeadersMap(m.headers().dynamics()));
            out.append(")");
            out.append(i < models.size() - 1 ? ",\n" : "));\n\n");
        }
    }

    private void emitParam(StringBuilder out, ParamModel p) throws SkipGeneration {
        switch (p) {
            case ParamModel.Simple(var kind, var index, var name, var defaultValue) ->
                    out.append("new io.vidocq.cyrano.spi.gen.ClientParamDescriptor.")
                            .append(kind).append("(").append(index).append(", ")
                            .append(quote(name)).append(", ").append(quoteOrNull(defaultValue)).append(")");
            case ParamModel.Body(var index) -> out.append("new io.vidocq.cyrano.spi.gen.ClientParamDescriptor.Body(")
                    .append(index).append(")");
            case ParamModel.Bean(var index, var beanType, var fields) -> {
                out.append("new io.vidocq.cyrano.spi.gen.ClientParamDescriptor.Bean(")
                        .append(index).append(", ")
                        .append(erasedClassLiteral(beanType))
                        .append(", java.util.List.of(");
                for (int f = 0; f < fields.size(); f++) {
                    if (f > 0) out.append(", ");
                    BeanFieldModel field = fields.get(f);
                    out.append("new io.vidocq.cyrano.spi.gen.ClientParamDescriptor.BeanField(")
                            .append(quote(field.fieldName())).append(", ")
                            .append("io.vidocq.cyrano.spi.gen.ClientParamDescriptor.BeanField.Kind.")
                            .append(field.kind()).append(", ")
                            .append(quote(field.name())).append(", ")
                            .append(quoteOrNull(field.defaultValue())).append(")");
                }
                out.append("))");
            }
        }
    }

    private void emitMethod(StringBuilder out, MethodModel m, int index) {
        ExecutableElement element = m.element();
        ExecutableType resolved = m.resolved();
        TypeMirror returnType = resolved.getReturnType();
        boolean isVoid = returnType.getKind() == TypeKind.VOID;
        List<? extends TypeMirror> paramTypes = resolved.getParameterTypes();

        out.append("\n    @java.lang.Override\n    public ")
                .append(returnType).append(' ').append(element.getSimpleName()).append('(');
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) out.append(", ");
            if (element.isVarArgs() && i == paramTypes.size() - 1) {
                out.append(((javax.lang.model.type.ArrayType) paramTypes.get(i)).getComponentType())
                        .append("... p").append(i);
            } else {
                out.append(paramTypes.get(i)).append(" p").append(i);
            }
        }
        out.append(") {\n");
        out.append("        java.lang.Object[] args = new java.lang.Object[] {");
        for (int i = 0; i < paramTypes.size(); i++) {
            out.append(i > 0 ? ", p" : " p").append(i);
        }
        out.append(paramTypes.isEmpty() ? "};\n" : " };\n");
        out.append("        try {\n");
        if (isVoid) {
            out.append("            this.invoker.invoke(this, ").append(index).append(", args);\n");
        } else {
            out.append("            return (").append(castType(returnType))
                    .append(") this.invoker.invoke(this, ").append(index).append(", args);\n");
        }
        out.append("        } catch (java.lang.RuntimeException | java.lang.Error e) {\n")
                .append("            throw e;\n")
                .append("        } catch (java.lang.Exception e) {\n")
                .append("            throw new java.lang.RuntimeException(e);\n")
                .append("        }\n")
                .append(MEMBER_END);
    }

    /** Boxed cast for primitives (auto-unboxes on return), source type otherwise. */
    private String castType(TypeMirror returnType) {
        return switch (returnType.getKind()) {
            case BOOLEAN -> "java.lang.Boolean";
            case BYTE -> "java.lang.Byte";
            case SHORT -> "java.lang.Short";
            case INT -> "java.lang.Integer";
            case LONG -> "java.lang.Long";
            case CHAR -> "java.lang.Character";
            case FLOAT -> "java.lang.Float";
            case DOUBLE -> "java.lang.Double";
            default -> returnType.toString();
        };
    }

    private String erasedClassLiteral(TypeMirror type) throws SkipGeneration {
        TypeMirror erased = types.erasure(type);
        if (erased.getKind() == TypeKind.TYPEVAR || erased.getKind() == TypeKind.WILDCARD) {
            throw new SkipGeneration("unresolvable parameter type " + type);
        }
        return erased + ".class";
    }

    private String stringList(List<String> values) {
        StringBuilder sb = new StringBuilder("java.util.List.of(");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quote(values.get(i)));
        }
        return sb.append(")").toString();
    }

    private String staticHeadersMap(Map<String, List<String>> statics) {
        if (statics.isEmpty()) return "java.util.Map.of()";
        StringBuilder sb = new StringBuilder("java.util.Map.ofEntries(");
        boolean first = true;
        for (Map.Entry<String, List<String>> e : statics.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append("java.util.Map.entry(").append(quote(e.getKey())).append(", ")
                    .append(stringList(e.getValue())).append(")");
        }
        return sb.append(")").toString();
    }

    private String dynamicHeadersMap(Map<String, DynamicModel> dynamics) {
        if (dynamics.isEmpty()) return "java.util.Map.of()";
        StringBuilder sb = new StringBuilder("java.util.Map.ofEntries(");
        boolean first = true;
        for (Map.Entry<String, DynamicModel> e : dynamics.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append("java.util.Map.entry(").append(quote(e.getKey()))
                    .append(", new io.vidocq.cyrano.spi.gen.DynamicHeaderDescriptor(")
                    .append(quote(e.getValue().methodName())).append(", ")
                    .append(e.getValue().required()).append("))");
        }
        return sb.append(")").toString();
    }

    private void writeServicesFile() {
        if (servicesWritten || factoryFqns.isEmpty()) return;
        servicesWritten = true;
        try {
            var resource = filer.createResource(StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/services/io.vidocq.cyrano.spi.gen.ClientProxyFactory");
            try (Writer w = resource.openWriter()) {
                for (String fqn : factoryFqns) {
                    w.write(fqn);
                    w.write('\n');
                }
            }
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                    "Could not write ClientProxyFactory services file (naming-convention "
                            + "resolution still applies): " + e);
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers (mirror CyranoInterfaceScanner semantics)
    // ------------------------------------------------------------------

    private static String pathOf(Element element) {
        Path p = element.getAnnotation(Path.class);
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
        if (base.equals("/")) return sub;
        return base + sub;
    }

    private static List<String> mediaTypes(Consumes c) {
        return c == null ? List.of() : List.of(c.value());
    }

    private static List<String> mediaTypes(Produces p) {
        return p == null ? List.of() : List.of(p.value());
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String quoteOrNull(String value) {
        return value == null ? "null" : quote(value);
    }

    private void note(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.NOTE, "[cyrano-processor] " + message, element);
    }
}
