# Cyrano — Attack plan

> MicroProfile Rest Client 4.0 implementation in the Vidocq style: zero third-party libraries
> (Jakarta EE / MicroProfile specs allowed), JDK 25, virtual threads, strict Java Modules,
> proxy generation via Class-File API (JEP 484), JDK `java.net.http.HttpClient` transport,
> optional CDI integration via Vauban.

## Design principles

| Principle | Concrete application |
|---|---|
| Zero third-party libraries | No RESTEasy Client, CXF, Jersey Client, or OkHttp in `cyrano-core`. Only compiled spec APIs: `microprofile-rest-client-api` + `jakarta.ws.rs` + `jakarta.json.bind`. Transport: `java.net.http.HttpClient` (pure JDK). |
| Allowed Jakarta / MicroProfile specs | `cyrano-cdi-vauban` may depend on `jakarta.enterprise.cdi-api`, `jakarta.inject-api`, `jakarta.annotation-api`. `cyrano-core` is limited to `jakarta.ws.rs` + `jakarta.json.bind`. |
| Class-File API (JEP 484) | No `java.lang.reflect.Proxy`. Generate real classes named `Cyrano$<Interface>` via the JDK 25 `ClassFile` API. Cached in `CyranoProxyCache` (ConcurrentHashMap, lazy-init). Compatible with GraalVM, Leyden CDS, readable stack traces. |
| Virtual threads | No `synchronized`, no `ThreadLocal`. `HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor())`. `CompletionStage<T>` handled via `HttpClient.sendAsync` on virtual threads. |
| Strict Java Modules | `module-info.java` everywhere, non-exported `internal.*` packages, SPI via `provides/uses`. No unjustified `opens`. |
| Strict TDD | Red → Green → Refactor. Tests written before production code. Systematic citation of the MicroProfile Rest Client 4.0 spec section in test JavaDoc. |
| 100% PASS TCK | Hard contract on the MicroProfile Rest Client 4.0 TCK before any structural merge. |
| AOT-friendly | No dynamic proxy `java.lang.reflect.Proxy`, no `setAccessible(true)`. Named classes generated via Class-File API → referenceable in GraalVM `reflect-config.json` (but the goal is to avoid any manual AOT config). |

## Methodology: TDD + TCK as parallel safeguards

Cyrano is developed with **strict TDD** (Red → Green → Refactor). No production line
is written before a test justifies it. Beyond the internal TDD cycle:

- **Layer 1 — TDD unit tests**: drive the design of each class.
- **Layer 2 — `cyrano-core` integration tests**: complete client scenarios with inline JDK
  mock server (`com.sun.net.httpserver.HttpServer`), multi-method HTTP, JSON serialization.
- **Layer 3 — official TCK** (`microprofile-rest-client-tck:4.0`): 100% PASS contract before
  any structural merge. Module outside the reactor (POM Model 4.0.0).

## MicroProfile Rest Client 4.0 spec recap — key points

### Client interface (§3)

```java
@RegisterRestClient(baseUri = "https://api.example.com")
@Path("/users")
public interface UserService {
    @GET @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    User getUser(@PathParam("id") long id);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createUser(User user);

    @GET
    CompletionStage<List<User>> listUsersAsync();
}
```

### Configuration (§5)
- Base URI: `@RegisterRestClient(baseUri=...)` or `<interface.fqn>/mp-rest/url` via MP Config.
- Registrable providers: `ResponseExceptionMapper`, `ClientRequestFilter`, `ClientResponseFilter`,
  `MessageBodyReader`, `MessageBodyWriter`, `ParamConverter`.

### CDI (§6)
- `@RegisterRestClient` on the interface → CDI bean qualified `@RestClient` + `@Default`.
- `@Inject @RestClient UserService client` → injected Cyrano proxy.
- Default CDI scope: `@Dependent` (spec §6.3); overridable via `@RegisterProvider`.

### Exception mapping (§7 + §8)
- `ResponseExceptionMapper<E extends Throwable>`: converts HTTP responses into exceptions.
- Default mapper: any HTTP response ≥ 400 → `WebApplicationException` (§8, priority 1).

### Async (§9)
- `CompletionStage<T>` return: non-blocking call via `HttpClient.sendAsync`.
- Virtual threads: `CompletionStage` completed on a virtual thread.

### Parameters (§3 + §4)
- `@PathParam`, `@QueryParam`, `@HeaderParam`, `@CookieParam`, `@FormParam`, `@MatrixParam`
- `@BeanParam`: aggregates multiple parameters into an object
- `@ClientHeaderParam`: static or dynamic headers (computed method)
- `@DefaultValue`: default value if the parameter is absent

---

## Phases

### M0 — Bootstrap

- [x] `.sdkmanrc` (`java=25-tem`, `maven=3.9.16`)
- [x] `.gitignore`, `.mvn/maven.config`
- [x] `pom.xml` parent (Model 4.1.0, multi-module, dependency management Jakarta + MicroProfile Rest Client)
- [x] `CLAUDE.md`, `AGENTS.md`, `ROADMAP.md` (this file) ✅
- [x] Java Modules validation: `microprofile-rest-client-api:4.0` has **neither** `Automatic-Module-Name`
      **nor** `module-info.class`. Introduced an explicit repackage module
      `cyrano-mp-rest-client-api` (`io.vidocq.cyrano.mp.rest.client.api`) to keep
      a `jlink`-compatible Java Modules graph.
- [x] Creation of modules with skeleton `pom.xml` + `module-info.java`:
      `cyrano-api`, `cyrano-core`, `cyrano-cdi-vauban` + `cyrano-tck` (outside reactor)
- [x] `run-official-tck-mp-rest-client-4.0.sh`
- [x] Validation `./mvnw -ntp install -DskipTests` succeeds (reactor + standalone cyrano-tck)
- [x] Smoke test `CyranoTckSmokeTest` : 3 tests PASS

**Deliverable:** green build, strict Java Modules validated on all skeleton modules, smoke TCK compilable. ✅

---

### M1 — Core: interface scanning + Class-File API proxy + JDK transport

**Scope spec:** §3 (client interface), §3.1 (base parameter types), §4 (invocation).

| Task | Notes | State |
|---|---|---|
| `CyranoRestClientBuilder` implements `RestClientBuilder` | `baseUri(URI)`, `build(Class<T>)`, `register(Class<?>)` | [x] |
| `CyranoInterfaceScanner` | Scans JAX-RS annotations on the interface + methods: `@Path`, HTTP verbs, `@PathParam`, `@QueryParam` | [x] |
| `RequestSpec` record | Captures the HTTP method, path template, and parameter bindings — immutable, built by the interface scanner | [x] |
| `CyranoProxyGenerator` — Class-File API (JEP 484) | Generates a named class `Cyrano$<SimpleName>` implementing the interface in the **interface package** (`privateLookupIn`). Each method delegates to `CyranoInvocationHandler.invoke(methodIndex, args)`. No `java.lang.reflect.Proxy`. | [x] |
| `CyranoProxyCache` | `ConcurrentHashMap<Class<?>, Entry>`, `computeIfAbsent`, thread-safe. | [x] |
| `CyranoInvocationHandler` | Resolves path/query parameters, builds `HttpRequest`, delegates to transport. | [x] |
| `CyranoHttpTransport` | `java.net.http.HttpClient` + `VirtualThreadPerTaskExecutor`. `send()` (sync) and `sendAsync()` (async). | [x] |
| Return types: `String`, `Response`, primitives | HTTP response → return value mapping (Response via minimal `CyranoLightResponse` — M1) | [x] |
| TDD unit tests `CyranoInterfaceScannerTest` | 6 tests — spec coverage §3 / §3.1 | [x] |
| TDD unit tests `CyranoProxyGeneratorTest` | 4 tests — verifies class name, interface implementation, delegation to handler | [x] |
| Integration tests with JDK `com.sun.net.httpserver.HttpServer` | 7 `CyranoEndToEndTest` tests — GET path-param, GET query-param, POST, primitive return, proxy cache | [x] |
| SPI `RestClientBuilderResolver` + `META-INF/services` + Java Modules `provides` | `CyranoRestClientBuilderResolver` exported via `io.vidocq.cyrano.runtime` | [x] |

**M1 decisions:**
- `ClassFile.of().build(...)` to generate bytecode; `MethodHandles.privateLookupIn(iface, ...)`
  + `defineClass(bytes)` to load it into **the user interface module** (not
  cyrano-core), which allows implementing package-private interfaces and avoids
  inter-module visibility issues. Consequence: client modules will need to open
  their package to `io.vidocq.cyrano.core` (documented at M3).
- `CyranoInvocationHandler.invoke(int methodIndex, Object[] args)`: stable signature called
  by every generated proxy; non-final to allow stubbing in tests (TDD without Mockito).
- `HttpClient` configured with HTTP/2 (automatic HTTP/1.1 fallback) + `VirtualThreadPerTaskExecutor`.
- Return `Response`: minimal `CyranoLightResponse` in M1; replaced by use of
  `RuntimeDelegate` (Cassini) in M2.

**Deliverable:** `RestClientBuilder.newBuilder().baseUri(uri).build(MyService.class).getUser(1L)`
performs a real HTTP GET call and returns the response — validated by 17 unit tests + 5
TCK smoke tests. ✅

---

### M2 — Complete request/response mapping

**Scope spec:** §3.1 (all parameter types), §4.2 (MessageBody), §5 (providers).

| Task | Notes | State |
|---|---|---|
| `@HeaderParam`, `@CookieParam`, `@FormParam`, `@MatrixParam` | Parameter → HTTP header/cookie/form/matrix binding | [x] |
| `@BeanParam` | Aggregates multiple parameter annotations into a POJO (fields annotated `@PathParam`/`@QueryParam`/`@HeaderParam`/`@CookieParam`/`@FormParam`/`@MatrixParam`) | [x] |
| `@ClientHeaderParam` (static and dynamic) | Static header (literal values) or dynamic (`{methodName}` → interface `default` method, signature `()` or `(String)`) — at type or method level | [x] |
| `@DefaultValue` | Default value if parameter is `null` (parameter or `@BeanParam` field) | [x] |
| `@Consumes` / `@Produces` | Inject `Content-Type` header (from `@Consumes`) and `Accept` (from `@Produces`) | [x] |
| Request body serialization: Jakarta JSON-B | `Jsonb.toJson(body)` for POJO request bodies (unannotated parameter) | [x] |
| Response body deserialization: Jakarta JSON-B | `Jsonb.fromJson(responseBody, returnType)` for POJO + generic returns | [x] |
| `Optional<T>` return | 404 → `Optional.empty()`, 200 → `Optional.of(deserializedValue)` | [x] |
| `List<T>`, `Set<T>`, `Map<K,V>` return | JSON array/object deserialization → Java collection | [x] |
| `ResponseExceptionMapper<E>` SPI | Registerable via `RestClientBuilder.register(...)` — applied in priority order | [x] |
| Default exception mapper (§8) | HTTP ≥ 400 → `WebApplicationException` (priority 1) built with `CyranoLightResponse` | [x] |
| TDD param binding tests | 17 `CyranoMappingM2Test` tests — one test per parameter type + `@DefaultValue` + `@BeanParam` cases | [x] |
| TDD JSON serialization tests | Serialized POJO POST, deserialized POJO GET, `List`/`Set`/`Optional` return | [x] |

**M2 decisions:**
- Extended sealed `ParamBinding`: `Path`, `Query`, `Header`, `Cookie`, `Form`, `Matrix`, `Body`,
  `Bean(List<FieldBinding>)` — exhaustive resolution via `switch` (JDK 25 pattern matching).
- Generated bytecode now passes `this` (the proxy) to the handler in addition to the `methodIndex` and
  `args` — required to invoke the `default` methods referenced by
  `@ClientHeaderParam(value="{methodName}")`. Signature: `handler.invoke(Object, int, Object[])`.
- Champollion (`jakarta.json.bind.Jsonb`) is the only serializer; `cyrano-core` declares only
  the `jakarta.json.bind` API — `champollion-jsonb` + `champollion-jsonp` are in `runtime` scope
  (test scope here for cyrano-core, runtime scope at final deployment).
- `ResponseExceptionMapper` are applied in ascending priority order (lower priority =
  higher precedence); the default mapper is implicit, triggered if no user mapper `handles()`.
- Form-encoded vs JSON body: if a single `@FormParam` is present → `application/x-www-form-urlencoded`,
  otherwise if an unannotated parameter is present → JSON-B with `Content-Type` from `@Consumes` or `application/json`.

**Deliverable:** POJO correctly serialized/deserialized; headers/cookies/form sent;
functional exception mapping for HTTP error codes. Validated by **34/34 tests** in
`cyrano-core` (6 scanner + 4 proxy generator + 7 M1 end-to-end + 17 M2 mapping). ✅

---

### M3 — Vauban CDI integration (`cyrano-cdi-vauban`)

**Scope spec:** §6 (CDI integration), §6.1 (discovery), §6.2 (injection), §6.3 (scope), §6.4 (config).

| Task | Notes | State |
|---|---|---|
| BCE `CyranoRestClientExtension` | `@Enhancement(types=Object.class, withAnnotations=RegisterRestClient.class)` — detects interfaces annotated `@RegisterRestClient` (see `CyranoRestClientCdiExtension`) | [x] |
| CDI producer per discovered interface | `SyntheticBean` via `@Synthesis` + `CyranoRestClientSyntheticCreator` which calls `RestClientBuilder.newBuilder().baseUri(uri).build(iface)` | [x] |
| `@RestClient` qualifier | Synthetic bean qualified `org.eclipse.microprofile.rest.client.inject.RestClient` (spec §6.2) | [x] |
| Base URI from `@RegisterRestClient(baseUri=...)` | Low priority: annotation value, resolved by `CyranoBaseUriResolver` | [x] |
| Base URI from MicroProfile Config (Ravel) | `<interface.fqn>/mp-rest/url` (priority 1) then `<configKey>/mp-rest/url` (priority 2) — reflective `ConfigProvider` detection to remain optional | [x] |
| Default CDI scope `@Dependent` (§6.3) | Overridable via `@ApplicationScoped` / `@RequestScoped` / `@SessionScoped` / `@Singleton` on the interface | [x] |
| Deployment validation | `@RegisterRestClient` interface without base URI or config → `IllegalStateException` (to be mapped to runtime `DeploymentException`, §M3 next iteration if TCK required) | [x] |
| Integration tests with embedded Vauban | `@Inject @RestClient PingApi client` → injected proxy + real HTTP call on inline JDK `HttpServer` (2 `CyranoRestClientCdiIntegrationTest` tests + 7 `CyranoBaseUriResolverTest` unit tests) | [x] |

**M3 decisions:**
- MP Config (Ravel) resolved **reflectively** on `org.eclipse.microprofile.config.ConfigProvider` —
  cyrano-cdi-vauban declares no compile dependency on `microprofile-config-api`. If
  the API cannot be loaded, `CyranoBaseUriResolver.defaultMpConfigLookup()` returns a
  `key → Optional.empty()` function (graceful degradation requested by AGENTS.md).
- The **BCE pipeline** uses `@Enhancement` (CDI Lite §3.8) to observe annotated interfaces
  without making them managed beans, then `@Synthesis` to register a
  `SyntheticBean` per interface. `SyntheticBeanCreator` receives annotation literal values
  via `withParam(...)` and resolves the base URI at bean instantiation time
  (runtime, not build-time — allows MP Config override without rebuild).
- **Vauban discovery**: the BCE is published both through `META-INF/services/...BuildCompatibleExtension`
  (classpath) and through `provides ... with` in `module-info.java` (strict Java Modules). A
  `META-INF/vauban-beans.list` file completes support for jlink/Java Modules environments where
  ServiceLoader classpath discovery is not enough — same pattern as ravel-cdi-vauban.
- **compile-module-info workaround**: `exports io.vidocq.cyrano.cdi` removed because the package
  contains only `package-info.java`, which the `prepare-package`/`compile-module-info`
  phase does not see in `target/classes` (incompatible with maven-compiler-plugin 4.0.0-beta-4 on
  a separate incremental compilation). Only `io.vidocq.cyrano.cdi.internal` is exported;
  this package can be promoted to a stable public SPI if needed in M5.

**Deliverable:** `@Inject @RestClient UserService client` injected by Vauban and working
in integration test with inline JDK mock server. Validated by **9/9 tests** in
`cyrano-cdi-vauban` (7 `CyranoBaseUriResolverTest` unit tests + 2 integration
`CyranoRestClientCdiIntegrationTest` tests). ✅

---

### M4 — MicroProfile Rest Client 4.0 TCK

**Scope:** official MicroProfile Rest Client 4.0 validation + reproducible script.

| Task | Notes | State |
|---|---|---|
| `cyrano-tck/pom.xml` standalone Model 4.0.0 | Same as `cassini-tck`/`knock-tck`/`ravel-tck` — outside reactor | [x] |
| Arquillian runner + official `microprofile-rest-client-tck:4.0` harness | `CyranoDeployableContainer` (Arquillian *Local* protocol) + `CyranoArquillianExtension` + `WireMockProbeListener` (boot WireMock via static block) | [x] |
| TCK mock backend: embedded WireMock 3.10 | WireMock 127.0.0.1:8765 — JVM-static singleton started when the TestNG listener loads (before any `@BeforeMethod`) | [x] |
| Cyrano → mock backend configuration | The TCK itself injects the target URI via `RestClientBuilder.baseUri(getServerURI())` and `WireMock.configureFor("127.0.0.1", 8765)` | [x] |
| `run-official-tck-mp-rest-client-4.0.sh` | Modes: smoke / all / `-Dtest=TestName`; report `target/tck-report.txt` | [x] |
| M4-1 iteration: initial fixes (M4-1 baseline) | `@ClientHeaderParam(required)`; static FQN compute; interface↔method override; original exception propagation; see `TCK.md` | [x] |
| M4-2 iteration: `ClientRequestFilter` / `ClientResponseFilter` SPI | Client-side JAX-RS filter pipeline: `CyranoClientRequestContext` + `CyranoClientResponseContext` + priority ordering (ascending request / descending response); `abortWith(Response)` short-circuits transport; `org.eclipse.microprofile.rest.client.invokedMethod` property exposed; +12 TCK PASS tests | [x] |
| M4-3 iteration: CDI bootstrap in the runner | Embedded Vauban for `CDI.current()` (unblocks ~25 `cditests/*` tests) | [x] |
| M4-4 iteration: `connectTimeout` / `readTimeout` / `followRedirects` | Options propagated to `HttpClient`, re-enable `**/timeout/**` | [x] |
| M4-5 iteration: `QueryParamStyle` + `@EntityPart` + interface inheritance + `Feature` SPI | Configurable URL encoding, multipart, recursive scan, providers | [x] |
| **Contract score: 100% PASS** | M4-1 baseline: ~26 PASS / 160 (excluding SSE/SSL/timeout) ≈ 16%. Projection M4-2..M4-5: ≥ 80% | [ ] |

**Architecture of `CyranoDeployableContainer`:**
- Arquillian *Local* container — no-op deploy/undeploy: Cyrano is a client, so there is no JAX-RS server resource to deploy.
- WireMock plays the role of HTTP backend (the TCK 4.0 spec relies on it via `WiremockArquillianTest`).
- `WireMockProbeListener` (TestNG `ITestNGListener`) starts WireMock as soon as the runner loads — *before* `@BeforeMethod`. Arquillian binding (`LoadableExtension.register()`, `DeployableContainer.start()`) proved too late (called after `@AfterSuite` for the *Local* protocol).

**M4-2 decisions — filters pipeline (delivered):**
- `CyranoClientRequestContext` and `CyranoClientResponseContext` expose the JAX-RS §6.3
  API without bringing in any server-side JAX-RS impl: URI, method, headers (`MultivaluedMap`),
  entity (unserialized POJO), properties, `abortWith`. JSON-B serialization is
  deferred to `buildHttpRequest()`, **after** the filters pipeline — a filter can therefore
  modify the entity before transport.
- Priority ordering: ascending for request filters (lowest priority =
  most priority, runs first — `Priorities.AUTHENTICATION = 1000`),
  descending for response filters (reverse order, per JAX-RS §6.3).
  Priority is taken in this order: (1) contract map passed to `register(...)`,
  (2) `@Priority` (detected reflectively to avoid a compile-time dependency
  on `jakarta.annotation-api`), (3) `Priorities.USER = 5000`.
- `abortWith(Response)` short-circuits transport: `CyranoClientResponseContext`
  is derived from the aborted `Response` (without calling `HttpClient`), then response
  filters run normally.
- Standard property `org.eclipse.microprofile.rest.client.invokedMethod` (MP Rest
  Client §4.2) injected into the context before filters — targeted by the TCK
  `InvokedMethodRequestFilter`.

**M4-3 decisions — CDI runner bootstrap (delivered):**
- The Arquillian *Local* container now starts a Vauban container per test archive
  via `VaubanTckBootstrap.deploy(Archive)` and shuts it down via
  `VaubanTckBootstrap.undeploy()` (`deploy/undeploy` cycle of `CyranoDeployableContainer`).
- `META-INF/microprofile-config.properties` is extracted from the ShrinkWrap archive
  (WAR/JAR, including `WEB-INF/lib/*.jar`) and projected into `TckConfigBridge`
  to guarantee consistent MP Config values on the `CDI.current()` side.
- Archive classes (including interfaces) are injected into the Vauban bootstrap
  so the Cyrano BCE (`@RegisterRestClient`) discovers CDI clients
  exactly like in the official TCK packaging.

**M4-4 decisions — timeouts + redirects (delivered):**
- `connectTimeout` is applied to the JDK `HttpClient.Builder` (`connectTimeout(Duration)`),
  while `readTimeout` is applied per request via `HttpRequest.Builder.timeout(Duration)`
  — this split matches the `java.net.http` model exactly.
- `followRedirects(false)` becomes `HttpClient.Redirect.NEVER` (default value), and
  `followRedirects(true)` becomes `HttpClient.Redirect.NORMAL`; 301/302/303/307 redirect tests
  now pass both programmatically and in CDI.
- On the CDI side, `CyranoRestClientSyntheticCreator` now reads the MP Config properties
  `<fqn>/mp-rest/followRedirects`, `<fqn>/mp-rest/connectTimeout`,
  `<fqn>/mp-rest/readTimeout` with `<configKey>/...` fallback and applies them to the
  `RestClientBuilder` before `build(...)`.
- HTTP responses now expose their headers in a **case-insensitive** way
  (`Location`, `Content-Type`, etc.) in `CyranoClientResponseContext` and
  `CyranoLightResponse`, aligning Cyrano with the HTTP semantics expected by the TCK.

**Suites disabled at M4-1** (see `TCK.md`):
- `**/ssl/**`: requires unimplemented `RestClientBuilder.trustStore(...)`.
- `**/timeout/**`: requires unimplemented `connectTimeout`/`readTimeout`.
- `**/sse/**`: SSE out of M0-M5 scope (see *Open decisions*).

**M4-1 deliverable:** functional Arquillian + WireMock runner, representative
subset of the spec covered; gap analysis in `TCK.md`. Final M4 deliverable: MicroProfile
Rest Client 4.0 TCK **100% PASS** via `./run-official-tck-mp-rest-client-4.0.sh all`.

---

### M5 — Vidocq ecosystem integration

**Scope:** integrate Cyrano into `vidocq` as the default REST client.

| Task | Notes | State |
|---|---|---|
| `docs/integration-cassini.md` documentation | Use Cyrano to call external Cassini services | [x] |
| `docs/integration-vidocq.md` documentation | Cyrano configuration in vidocq, base URL via Ravel | [x] |
| ADR-001 proxy generation strategy (Class-File API vs reflection) | Rationale, AOT, jlink, GraalVM | [x] |
| Module wrapper `vidocq-runtime-cyrano-rest-client-extension` in `vidocq` | Activates Cyrano through a single dependency, with no additional Java code — to be delivered in the `vidocq` repository | [ ] |
| ServiceLoader BCE (`META-INF/services/...BuildCompatibleExtension`) | `CyranoRestClientCdiExtension` exposed through the standard CDI 4.1 contract, covered by `CyranoRestClientCdiExtensionDiscoveryTest` | [x] |
| `module-info.java` `provides ... with` | Java Modules for service files, validated by the BCE discovery test | [x] |

**Deliverable:** complete documentation, installable wrapper module, Cyrano available in
any vidocq deployment through a single dependency.

**Actual state in the Cyrano repo:** M5 is complete for the scope present here (documentation,
ServiceLoader + Java Modules exposure, non-regression test). The only remaining item lives in the
external `vidocq` repository: the aggregation wrapper module.

---

## Priority order — why this one?

1. **M1 (proxy + transport)** first: the Class-File API generation engine and JDK transport
   are the core. Nothing else can be tested without them.
2. **M2 (mapping)** before M3 (CDI): parameter bindings and JSON serialization
   are used in both programmatic and CDI modes. Validate the core before attaching
   a CDI container to it.
3. **M3 (CDI)**: optional module. Can be developed in parallel with M2 once
   `CyranoRestClientBuilder` is stabilized.
4. **M4 (TCK)**: conformance contract. The TCK is run at every milestone to verify
   the covered sections; 100% PASS locked before M5.
5. **M5 (integration)** last: do not pollute the other Vidocq projects before
   Cyrano is TCK-validated.

## Known risks

| Risk | Mitigation |
|---|---|
| `microprofile-rest-client-api:4.0` without `Automatic-Module-Name` or `module-info.class` | Create a repackage `cyrano-mp-rest-client-api` (same pattern as `ravel-mp-config-api`); validate from M0 |
| Class-File API JEP 484: unstable API or breaking change between JDK 25 EA builds | Encapsulate in `CyranoProxyGenerator`; pin JDK 25 GA via sdkmanrc; test on every EA build in CI |
| TCK requires a full CDI container (Weld) that Vauban does not support | Evaluate what the TCK really requires; if Weld is unavoidable, isolate it as test-scope only (never prod scope); document in `TCK.md` |
| ShrinkWrap Maven Resolver 3.3 vs JDK 25 incompatibility (unrecognized modules) | Same solution as knock-tck: separate classpath for ShrinkWrap in the runner |
| `@BeanParam`: recursive resolution complexity for sub-annotations | Implement in M2 with exhaustive tests before touching the TCK |
| Multipart/form-data handling (optional spec 4.0) | Check whether the TCK needs it; implement in M2 if so; otherwise defer |
| Ravel config absent from the module path during tests | `cyrano-cdi-vauban` must degrade gracefully (no NPE) if Ravel is absent |
| Async `CompletionStage<T>` and cancellation | Propagate `HttpRequest.cancel()` from the returned `CompletableFuture` |

## Confirmed decisions

- ✅ **Class-File API (JEP 484)** for proxy generation — no `java.lang.reflect.Proxy`
- ✅ **`java.net.http.HttpClient`** as transport — zero-dep, native virtual thread executor
- ✅ **champollion** as the only Jakarta JSON-B runtime implementation
- ✅ **`cyrano-core` standalone SE**: usable without CDI, without a container
- ✅ **`cyrano-cdi-vauban` separate**: optional module, not loaded if CDI is absent
- ✅ **Strict TDD** on all production modules
- ✅ **100% PASS TCK** as a hard contract
- ✅ **TCK outside the reactor** (standalone POM Model 4.0.0) — ShrinkWrap Maven Resolver 3.3 constraint
- ✅ **Virtual threads** for all HTTP calls (`sendAsync` + `VirtualThreadPerTaskExecutor`)

## Open decisions

- [ ] Should there be a `cyrano-chappe` module using Chappe as the HTTP client transport
      (instead of JDK `java.net.http`) for vidocq integration? Or is JDK sufficient?
      → JDK `java.net.http` is the default; `cyrano-chappe` would be an optional adapter.
- [ ] Support multipart/form-data (MIME multipart)? MP Rest Client 4.0 spec §3 mentions it.
      → To be confirmed in M2 by reading the targeted TCK test list.
- [ ] Support `@ClientHeadersFactory` (header propagation from an incoming context)?
      → Useful for propagating JWT/trace ID. Evaluate in M3/M4.
- [ ] Support `SSE` (Server-Sent Events) on the client side?
      → Out of M0-M5 scope; defer post-TCK.
- [ ] `cyrano-bench`: comparison vs RESTEasy Client / Jersey Client on the same JVM?
      → Add in M5 or post-M5; create `BENCH.md` as soon as the first number is measured.
