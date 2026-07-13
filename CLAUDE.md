# Cyrano - Claude Code Guidelines

> Cyrano de Bergerac (1619–1655) spoke on behalf of others, lending them his eloquence
> to woo those they could not reach alone.
> This is exactly what a typed REST client does: it speaks on behalf of the application code,
> lending it proxies and annotations to call remote services without the caller
> needing to know the underlying protocol or transport.

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` provided — use `sdk env`)
- The MicroProfile Rest Client 4.0 TCK is a **public Maven Central** artifact:
  `org.eclipse.microprofile.rest.client:microprofile-rest-client-tck:4.0`
  (unlike Jakarta TCKs, no need to install manually).
- **Java Modules note:** verify at M0 whether `microprofile-rest-client-api` has an
  `Automatic-Module-Name` or a `module-info.class`. If not, create a
  `cyrano-mp-rest-client-api` repackage module (same pattern as `ravel-mp-config-api`).

## Essential Commands

```bash
# Build reactor (without TCK)
./mvnw -ntp install -DskipTests

# Unit tests
./mvnw test

# TCK — smoke test only
./run-official-tck-mp-rest-client-4.0.sh

# TCK — full suite
./run-official-tck-mp-rest-client-4.0.sh all

# TCK — targeted test
./run-official-tck-mp-rest-client-4.0.sh -Dtest=TestName
```

> `cyrano-tck` is **out-of-reactor** (standalone POM Model 4.0.0) to work around
> ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0 — same constraint as `cassini-tck`,
> `foy-tck`, `champollion-tck`, `ravel-tck`, and `knock-tck`. Do not change this model.

## Architecture

Cyrano is a MicroProfile Rest Client 4.0 implementation with **zero third-party libraries**
(no RESTEasy Client, CXF, Jersey Client, OkHttp), only Jakarta EE / MicroProfile specs as
dependencies, virtual threads, strict Java Modules.

```
cyrano-api          ← Re-exposes the org.eclipse.microprofile.rest.client spec
                     (RestClientBuilder, @RegisterRestClient, ClientHeaderParam, etc.)
                     + spi.gen (ClientInvoker, ClientProxyFactory, descriptors) consumed
                     by APT-generated client code
cyrano-processor    ← APT processor: generates $$CyranoClient sources (proxy + literal
                     descriptor + ServiceLoader-able Factory) at compile time for every
                     @RegisterRestClient interface — the PRIMARY proxy path (CG-01)
cyrano-core         ← Implementation: ClientProxyRegistry resolution chain, interface
                     scanning + runtime proxy generation via Class-File API (JEP 484)
                     as FALLBACK, JDK HttpClient transport, param binding,
                     MessageBodyReader/Writer via Jakarta JSON-B (champollion)
cyrano-cdi-vauban   ← CDI Vauban integration: BCE @RegisterRestClient, @Inject @RestClient,
                     base URL config via MicroProfile Config (Ravel)
cyrano-tck          ← Official MicroProfile Rest Client 4.0 TCK runner (OUT OF REACTOR)
```

**Client call flow:**
`@Inject @RestClient MyService client` → proxy `MyService$$CyranoClient` (APT-generated
source) or `Cyrano$MyService` (runtime fallback) → `CyranoInvocationHandler`
→ `HttpRequest` construction (JDK `java.net.http`) → `CyranoHttpTransport` (virtual thread)
→ response deserialization (Jakarta JSON-B / champollion) → typed return value.

**Proxy resolution — APT first, runtime generation as fallback (codegen audit CG-01):**
`ClientProxyRegistry` resolves proxies in order (hit counters included, cassini pattern):
1. **ServiceLoader of `ClientProxyFactory`** — module-layer aware; a strict Java Modules user
   module declares `provides ClientProxyFactory with com.acme.MyApi$$CyranoClient$Factory`
   and keeps its client package fully encapsulated.
2. **Naming convention** — `Class.forName(iface.getName() + "$$CyranoClient")`, the class
   generated at compile time by `cyrano-processor` (`Filer.createSourceFile`, no bytecode
   manipulation). Its literal `ClientDescriptor` replaces the runtime annotation scan
   (`DescriptorConverter` rebuilds the internal `RequestSpec`s with targeted `getMethod`
   resolution only).
3. **Runtime fallback** — for interfaces compiled without the processor (e.g. pre-compiled
   TCK jars): `CyranoInterfaceScanner` (annotation scan) + `CyranoProxyGenerator`, which
   generates a real named class `Cyrano$<InterfaceName>` via the JDK 25 `ClassFile` API
   (JEP 484), cached by `CyranoProxyCache` and loaded with
   `MethodHandles.Lookup.defineClass`. No `java.lang.reflect.Proxy`, no ASM/Byte Buddy.

The processor never fails a build: any construct it cannot emit faithfully (including
definitions the runtime scanner would reject with `RestClientDefinitionException`) is
skipped with a compiler NOTE — the fallback preserves exact spec behaviour.

**Supported JAX-RS annotations (on client interfaces):**
- HTTP methods: `@GET`, `@POST`, `@PUT`, `@DELETE`, `@PATCH`, `@HEAD`, `@OPTIONS`
- Path: `@Path`, `@PathParam`, `@QueryParam`, `@MatrixParam`
- Headers: `@HeaderParam`, `@CookieParam`, `@ClientHeaderParam`
- Body: `@Consumes`, `@Produces`, `@FormParam`, `@BeanParam`
- Context: `@Context` (limited)

**Supported return types:**
- Primitives and their wrappers
- `String`, `jakarta.ws.rs.core.Response`
- POJO deserialized via Jakarta JSON-B (champollion)
- `Optional<T>`, `List<T>`, `Set<T>`, `Map<K,V>`
- `CompletionStage<T>` (async, via virtual threads)

## Architecture Constraints Not to Violate

1. **`cyrano-core` only depends on `jakarta.ws.rs` + `jakarta.json.bind`** (spec API) —
   no CDI, no JAX-RS server implementation (Cassini). Transport = `java.net.http.HttpClient`
   (pure JDK). Champollion is the JSON-B implementation provided at runtime.
2. **`cyrano-cdi-vauban` depends on `cyrano-core` + `jakarta.cdi`** but never the reverse —
   CDI integration is an optional module invisible from the core.
3. **No `java.lang.reflect.Proxy`** — generate real named classes via Class-File API
   (JEP 484). Advantage: AOT-compatible (GraalVM `native-image`, Leyden CDS), readable
   stack traces, no `setAccessible(true)`.
4. **Transport via `java.net.http.HttpClient`** — zero-dep, native virtual thread executor
   (`HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor())`).
5. **Strict Java Modules**: all modules have a `module-info.java`, `internal.*` packages
   not exported, SPI exposed only via `provides ... with`.
6. **No `synchronized`, no `ThreadLocal`** — virtual-thread-friendly. `ScopedValue`
   if context propagation is needed (e.g. request tracing).
7. **No `setAccessible(true)` in production** — use `MethodHandles.privateLookupIn`
   if internal access is needed. Document any Java Modules opening.
8. **Champollion = only JSON lib** — no Jackson, Gson, standalone third-party Jsonb.
   `cyrano-core` declares `requires jakarta.json.bind` (spec); champollion provided at runtime.
9. **MicroProfile Rest Client 4.0 TCK PASS at 100%** is a hard contract before any structural merge.

## Conventions

- **Explicit Java modules**: all modules have a `module-info.java`.
- **Packages**:
  - `io.vidocq.cyrano.spi.*` = stable public SPI (TransportAdapter, ExceptionMapper, interceptors)
  - `io.vidocq.cyrano.internal.*` = internal code (may break between versions)
- **Maven groupId**: `io.vidocq.cyrano`.
- **Records** for immutable objects (`RequestSpec`, `ResponseSpec`, `ParamBinding`);
  **sealed interfaces** for closed hierarchies (parameter types, dispatch results).
- **Exhaustive pattern matching** on switch — no `if/else if` chains.
- **JUnit 6** only for tests (BOM `org.junit:junit-bom` 6.x).
- **Language** — commit messages, Javadoc, and all `.md` file content must be written in **English**.

Cyrano is developed with **strict TDD**, in this order:

1. **Red** — write the test describing the expected behavior (cite the MicroProfile Rest Client 4.0
   spec section in JavaDoc comments). The test must fail for the right reason
   (compilation OK, assertion KO).
2. **Green** — write the minimum code to make the test pass.
3. **Refactor** — clean up while keeping tests green. Run the full module suite
   before any commit.

Concrete rules:

- **One test per public class**, named `<Class>Test`, in the same package (`src/test/java`).
- **No Mockito** — hand-written doubles or inline JDK `HttpServer` test servers.
- **Spec fixture tests**: for each referenced MicroProfile Rest Client 4.0 spec section,
  a test named `<method>_spec_section<X>_<Y>()`.
- **Lightweight mock server** for `cyrano-core` integration tests: JDK `HttpServer`
  (`com.sun.net.httpserver.HttpServer`) or minimal Chappe port — no third-party library.

## TCK — Technology Compatibility Kit

MicroProfile Rest Client TCK — run in an out-of-reactor module (`cyrano-tck`,
POM Model 4.0.0) to work around ShrinkWrap Maven Resolver 3.3:

| TCK | Artifact | Target |
|---|---|---|
| MicroProfile Rest Client 4.0 | `org.eclipse.microprofile.rest.client:microprofile-rest-client-tck:4.0` | 100% PASS (hard contract) |

The `run-official-tck-mp-rest-client-4.0.sh` script:

- supports `smoke` (default), `all`, and targeted `-Dtest=TestName`;
- installs the reactor locally (`mvn install -DskipTests`) before invocation;
- produces a `target/tck-report.txt` report with the PASS/FAIL/SKIP score.

**TCK runner architecture:**

The MicroProfile Rest Client TCK requires an HTTP backend serving as the target server.
The `CyranoDeployableContainer` (custom Arquillian, ~300 LOC, test-scope only):
- Starts an embedded Cassini+Chappe instance on a random port to serve TCK JAX-RS resources
  (server side).
- Configures Cyrano's `RestClientBuilder` to point to this server (client side).
- Reuses `CassiniTestHarness` (cassini-tck) as a shared test component.
- No dependency on Weld, Undertow, or any third-party container.

**Release discipline:**

- **No structural merge** on `cyrano-core`/`cyrano-cdi-vauban` without TCK PASS.
- Any challenges (disabled tests for spec interpretation or TCK bug) are
  documented in `TCK.md` with spec citation, test hash, and reactivation plan.

## AI Principles — Collaboration on This Repository

- **Plan mode by default** on any structural change (new module, new SPI,
  modification of proxy generator or HTTP transport).
- **APT first** (workspace codegen rule, audit CG-01): static source generation via
  `cyrano-processor` is the primary path; the Class-File API runtime generator is the
  documented fallback only. For anything resembling dynamic generation, still prefer
  JEP 484 + `MethodHandles.Lookup.defineClass` over `java.lang.reflect.Proxy`.
  Use the `classfile-codegen` agent to review any change to the bytecode fallback.
- **Balanced elegance**: prefer a simple design that passes the TCK over a perfect design
  that does not. Document trade-offs in ADRs (`docs/adr/`).
- **No laziness on specs**: cite the MicroProfile Rest Client 4.0 section in code
  comments when the implementation directly responds to it.
- **Zero third-party libraries**: Jakarta EE and MicroProfile specs are the only
  dependencies allowed in `provided`/`compile` scope. If an implementation library
  seems necessary, the decomposition is wrong.
- Use agents **`jpms-guardian`**, **`virtual-threads-reviewer`**,
  **`dependency-gatekeeper`**, **`classfile-codegen`** proactively on any `module-info.java`
  modification, concurrent code, `pom.xml`, or bytecode generator.
- If the rules in this file need updating, remember to align `AGENTS.md` accordingly
  so Copilot Code can reference it easily.

## Documentation (Antora) conventions

The project documentation lives in `docs/en` and `docs/fr` as Antora modules and is
aggregated by the **vidocq-docs** site, which provides a **shared UI bundle** (banner,
logo, fonts, colours, footer). **Never customise the documentation UI per project** —
all visual harmonisation is centralised in `vidocq-docs/ui-bundle`.

### Gold reference
**Vauban** is the reference implementation for documentation structure. Mirror its
`docs/en` + `docs/fr` layout when creating or updating docs. **Chappe** (HTTP server)
and **Vidocq** (runtime orchestrator) are *special cases*, not references: they are not
Jakarta EE / MicroProfile spec implementations.

### Repository layout
- `docs/en/antora.yml` → `name: <project>`, `title:`, `version: ~`, `nav:`, `lang: en`.
- `docs/fr/antora.yml` → `name: <project>-fr`, same `title`, `lang: fr`.
- Pages in `modules/ROOT/pages/`, navigation in `modules/ROOT/nav.adoc`, images in
  `modules/ROOT/images/`.
- **EN/FR parity**: every page exists in both languages with translated content.

### Canonical navigation (section order)
`index` → `getting-started` → `usage` → `concepts` → `internals` → `tck` →
`performance` → `reference` → `migration`

Multi-module projects (e.g. Vidocq, Mansart) may append `modules/*` / `sub-modules/*`
sub-pages after `migration`.

### TCK / Performance rule (not mutually exclusive)
- Every **spec implementation** — i.e. **all projects except Chappe and Vidocq** — MUST
  have a **`tck`** section documenting TCK coverage/status.
- Projects with a performance story (e.g. **Chappe**) keep their **`performance`** section.
- When **both** sections exist, order them **TCK first, then Performance**.
- **Chappe** and **Vidocq** do not require a `tck` section (not spec implementations).

### `index.adoc` structure
Follow Vauban's `index.adoc`: page title (`= <Project>`), `:description:`, a centred logo
(`image::<project>-logo.png[...,role=module-logo]`), a `[.lead]` paragraph, then
`== Origin of the name`, an `== At a glance` table, and ecosystem / quick-links sections.

### Logo
Provide `modules/ROOT/images/<project>-logo.png` (PNG), referenced from `index.adoc`.

> When you change these documentation rules, keep `AGENTS.md` and `CLAUDE.md` in sync.

## Terminology

Use **Java Modules** (or **Java module** for a single module) when referring to
the Java Platform Module System. Do **not** use the abbreviation **JPMS** — in
prose, identifiers, or documentation.
