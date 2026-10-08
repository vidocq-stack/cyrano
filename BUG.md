# BUG — Cyrano

Tracking of reproducible bugs in `cyrano` (internal issues, regressions, incorrect behaviours
not yet fixed). Vidocq workspace convention: short id, date, symptom,
minimal repro, root cause hypothesis, status.

---

## CYR-001 — Java Modules bypassed via manual copy of compile-scope JARs

- **Opened**: 2026-05-25
- **Last revisited**: 2026-06-03
- **Status**: ✅ FIXED 2026-10-07 — workaround removed (Vidocq/vidocq-parent#13)

### Symptom

The root `pom.xml` of cyrano uses `maven-dependency-plugin` (phase `initialize`) to
copy all compile-scope JARs into `target/javamodules/`, then passes
`--module-path ${project.build.directory}/javamodules` manually to the compiler.

### Minimal Repro

```bash
grep -n "javamodules\|module-path" cyrano/pom.xml
# reveals the two manually configured plugins
```

Without the workaround (removing the `<build>` block), `./mvnw -ntp clean install`
fails on `cyrano-mp-rest-client-api` at the testCompile phase with:

```
[ERROR] module not found: jakarta.annotation
[ERROR] module not found: jakarta.inject
[ERROR] module not found: jakarta.cdi
[ERROR] module not found: jakarta.ws.rs
```

### Root Cause (refined 2026-06-03)

Initial hypothesis (missing `Automatic-Module-Name`) was wrong for most deps:

- ✅ `microprofile-rest-client-api` — initial issue (automatic module only),
  now resolved by the internal repackage module `cyrano-mp-rest-client-api`
  which publishes a real `module-info.class`.
- ✅ `vauban-core`, `vauban-classloader-spi`, `champollion-jsonp`,
  `champollion-jsonb` — **already ship a real `module-info.class`** in their
  published JARs (verified via `unzip -l ~/.m2/repository/.../*.jar`).

The actual remaining cause is **`maven-compiler-plugin` 3.13.0 + Maven 3.9.16
not placing `requires static` dependencies on the `--module-path` automatically**
during the `testCompile` phase. `cyrano-mp-rest-client-api/module-info.java`
declares `requires static jakarta.cdi / jakarta.inject / jakarta.annotation`
(plus `requires transitive jakarta.ws.rs`); without the workaround,
javac receives those API JARs on the classpath instead of the module-path
and fails with `module not found`.

### Resolution Path

1. ✅ ~~Repackage `microprofile-rest-client-api` with an explicit
   `module-info.class`~~ — done via `cyrano-mp-rest-client-api`.
2. ⏳ **Upgrade** `maven-compiler-plugin` to a version that correctly routes
   `requires static` Java Modules deps to `--module-path` during testCompile.
   Track Apache `MCOMPILER` JIRA for the matching fix.
3. ⏳ Alternative: switch the affected `requires static` to non-static where
   feasible (would force the Jakarta APIs onto the compile-scope, raising
   the runtime footprint but simplifying the build).
4. ✅ ~~Narrow the workaround to `cyrano-mp-rest-client-api` only~~ —
   applied 2026-06-03. The `<build>` block that copied compile-scope JARs
   into `target/javamodules` for the whole reactor has been moved into
   `cyrano-mp-rest-client-api/pom.xml`. Verified: `cyrano-api`,
   `cyrano-core`, and `cyrano-cdi-vauban` build cleanly without it
   (BUILD SUCCESS, 69/69 tests pass). Only the repackage module retains
   the workaround.

### Resolution (2026-10-07)
The failure no longer reproduces on main: with the `target/javamodules` copy and the `--module-path` arguments
removed, `clean verify` passes with the same tests (152) and every produced jar (5) keeps the same module
descriptor. Most likely the failure dated from the Maven 4 RC / compiler-plugin 4.0.0-beta era (it does not come
back with compiler plugin 3.13 either). Workaround removed; the shared execution in vidocq-parent goes next
(Vidocq/vidocq-parent#13).

## BUG-20260712-01 — hardcoded implementation version constant in the published api artifact

- **Date** : 2026-07-12
- **Statut** : FIXED (branch fix/build-derived-version — ships with the next release)
- **Module touché** : Cyrano.IMPLEMENTATION_VERSION (cyrano-api/Cyrano.java)
- **Symptôme** : the artifact published on Maven Central as 0.2.0 reports a hardcoded
  "0.1.0-SNAPSHOT" implementation version — the constant was maintained by hand and never
  updated by the release train. Same class as vidocq BUG-20260704-01 (CLI banner).
- **Reproduction minimale** : read the constant from the published 0.2.0 jar.
- **Hypothèse de cause** : compile-time constant, no build filtering.
- **Investigations** :
  - 2026-07-12 : found by grepping for stale version strings after the issue #3 follow-up.
    Fixed: version.properties filtered by Maven next to the class, constant loaded at class
    init (same-module Java Modules resource, no opens). No longer compile-time-inlineable, which
    also protects future consumers from the javac inlining trap.

## BUG-20261008-01 — RestClientBuilder.newBuilder() throws ServiceConfigurationError on the module path

- **Date**: 2026-10-08
- **Status**: FIXED (branch pr/ybl/module-info-in-place — ships with the next release)
- **Affected modules**: `io.vidocq.cyrano.mp.rest.client.api`, `io.vidocq.cyrano.core` (module descriptors)
- **Symptom**: on the module path, no Rest Client can be created. `RestClientBuilder.newBuilder()` throws
  `ServiceConfigurationError: org.eclipse.microprofile.rest.client.spi.RestClientBuilderResolver: module
  io.vidocq.cyrano.mp.rest.client.api does not declare 'uses'`; a builder obtained from
  `CyranoRestClientBuilderResolver` directly fails the same way in cyrano-core (`RestClientBuilderListener:
  module io.vidocq.cyrano.core does not declare 'uses'`), at creation and again in `build()`. The CDI path
  (`CyranoRestClientSyntheticCreator`) calls `RestClientBuilder.newBuilder()`, so `@Inject @RestClient` is
  affected too. Invisible on the class path, where every test and the TCK run.
- **Minimal repro**: put cyrano-api, cyrano-core, cyrano-mp-rest-client-api and their dependencies on a plain
  `--module-path` and call `RestClientBuilder.newBuilder()`. Also reproduced on the Vidocq runtime: the
  vidocq-runtime-cassini-rest-example jlink image with the Rest Client extension added answers a request
  calling `RestClientBuilder.newBuilder()` with the `ServiceConfigurationError` above (Cyrano bricks in the
  boot layer, the application in the Vauban child layer).
- **Root cause**: the MicroProfile API (`RestClientBuilder.newBuilder()`,
  `RestClientBuilderResolver.instance()`) and Cyrano (`CyranoRestClientBuilderResolver`,
  `CyranoRestClientBuilder`) look services up with `ServiceLoader`, which a named module may only do for
  services its descriptor `uses`. Missing: `uses RestClientBuilderResolver` and `uses
  RestClientBuilderListener` in the repackaged API, `uses RestClientBuilderListener` and `uses
  RestClientListener` in cyrano-core. Nothing in the Vidocq runtime adds them: the Vauban application layer
  only synthesises `provides` (and only for re-layered application modules), and the `io.vidocq.cyrano`
  bricks stay in the boot layer.
- **Investigations**:
  - 2026-10-08: found while moving cyrano's tests to the module path (late module-info workaround removal).
    Fixed with the four `uses`; module-path tests in cyrano-mp-rest-client-api (`RestClientBuilderLookupTest`)
    and cyrano-core (`CyranoRestClientBuilderResolverTest`) look the resolver and the listeners up from
    another module (a jar defined in a child layer). Before the fix they failed with the errors above, as did
    55 existing cyrano-core tests once on the module path.

## BUG-20261008-02 — runtime-fallback proxy cannot be defined for an interface of another named module

- **Date**: 2026-10-08
- **Status**: FIXED (branch pr/ybl/rest-client-module-path — ships with the next release)
- **Affected module**: `io.vidocq.cyrano.core` (`CyranoProxyGenerator`, runtime fallback tier)
- **Symptom**: on the module path, `build()` of an interface without a generated `$$CyranoClient`, declared in
  another named module, fails even when that module follows the error message's advice
  (`opens <pkg> to io.vidocq.cyrano.core`): first `IllegalAccessException: module io.vidocq.cyrano.core does
  not read module <host>`, then, with that read added, `IllegalAccessError: class <pkg>.Cyrano$<Iface> (in
  module <host>) cannot access class io.vidocq.cyrano.internal.CyranoInvocationHandler (in module
  io.vidocq.cyrano.core) because module io.vidocq.cyrano.core does not export io.vidocq.cyrano.internal to
  module <host>`.
- **Minimal repro**: module `probe.app` (`requires io.vidocq.cyrano.core; opens probe.app to
  io.vidocq.cyrano.core;`) builds a client for its own `@Path` interface on a plain module path. It works only
  with `--add-reads io.vidocq.cyrano.core=probe.app --add-exports
  io.vidocq.cyrano.core/io.vidocq.cyrano.internal=probe.app`.
- **Root cause hypothesis**: the proxy is defined in the host's package (`privateLookupIn` +
  `defineClass`) and extends/uses `io.vidocq.cyrano.internal` types, but cyrano-core neither adds a read edge
  to the host module nor exports its internal package to it. A module may do both for itself at run time
  (`Module.addReads`, `Module.addExports` from cyrano-core), or the proxy could depend on exported SPI types
  only. Not live for clients generated by cyrano-processor (generated tier), nor on the class path (TCK).
- **Investigations**:
  - 2026-10-08: reproduced in a cyrano-core module-path test: an explicit module `cyrano.test.host`
    (`requires io.vidocq.cyrano.mp.rest.client.api; opens io.vidocq.cyrano.test.host to io.vidocq.cyrano.core;`,
    written with the Class-File API, defined in a child layer) fails with `IllegalAccessException: module
    io.vidocq.cyrano.core does not read module cyrano.test.host`. Fixed both halves: cyrano-core adds the read
    edge to the host module for itself (`Module.addReads`, allowed for the caller's own module), and the
    generated proxy no longer refers to `io.vidocq.cyrano.internal`: it holds a bound `MethodHandle` to
    `CyranoInvocationHandler.invoke` (called with `invokeExact`) and a `Runnable` for `close()`, both
    `java.base` types. The host module therefore needs nothing but the `opens` the error message names: it
    does not have to read cyrano-core, and cyrano-core exports nothing more. A zero-opens path exists already:
    clients generated by cyrano-processor. Defining the proxy elsewhere than in the interface's package (a
    dynamic module, as `java.lang.reflect.Proxy` does) would only help public interfaces of exported packages,
    so the `opens` stays the documented requirement of the runtime fallback.
- **Investigations**:
  - 2026-10-08: seen when moving the cyrano-processor and cyrano-cdi-vauban tests to the module path; their
    surefire `argLine` stands in for the read and the export until this is fixed.

## BUG-20261008-03 — RestClientBuilderListener.onNewBuilder runs up to three times per builder

- **Date**: 2026-10-08
- **Status**: FIXED (branch pr/ybl/rest-client-module-path — ships with the next release)
- **Affected module**: `io.vidocq.cyrano.core` (`CyranoRestClientBuilderResolver`, `CyranoRestClientBuilder.build`)
- **Symptom**: every `RestClientBuilderListener` is notified several times for one builder. A builder from
  `RestClientBuilder.newBuilder()` is notified by the MicroProfile API, again by `CyranoRestClientBuilderResolver.newBuilder()`,
  and once more by `build()`; a listener that registers a provider registers it again on the same builder (no-op, but
  a listener that counts or adds a non-idempotent configuration sees the duplicates). Module-path probe: 5 calls for
  3 builders.
- **Minimal repro**: a `RestClientBuilderListener` that counts its calls; `RestClientBuilder.newBuilder().baseUri(u).build(Api.class)`
  counts 3.
- **Spec**: MP Rest Client 4.0 API, `RestClientBuilderListener` Javadoc: implementations "will be notified when new
  RestClientBuilder instances are being constructed" and `onNewBuilder` "will be called when the RestClientBuilder is
  constructed, not when its build method is invoked". The API's own `RestClientBuilder.newBuilder()` does the
  notification (it loops over `ServiceLoader.load(RestClientBuilderListener.class)` after calling the resolver), so the
  implementation must not notify again — neither in the resolver nor in `build()`. TCK `RestClientBuilderListenerTest`
  only checks that the listener ran (a registered filter wins).
- **Root cause**: the resolver and `build()` each run their own listener loop on top of the API's.
- **Investigations**:
  - 2026-10-08: the module-path test (`CyranoRestClientBuilderResolverTest`) counted 2 calls after
    `RestClientBuilder.newBuilder()` and 3 after `build()`. Fixed: the resolver returns a bare builder and
    `build()` no longer runs builder listeners; `RestClientBuilder.newBuilder()` notifies each listener once. A
    builder taken from `CyranoRestClientBuilderResolver.newBuilder()` directly is not notified (the resolver is the
    SPI behind `newBuilder()`; Cyrano's own CDI path goes through `RestClientBuilder.newBuilder()`).

## BUG-20261008-04 — build() registered TCK providers by class name when no listener was found

- **Date**: 2026-10-08
- **Status**: FIXED (branch pr/ybl/rest-client-module-path — ships with the next release)
- **Affected modules**: `io.vidocq.cyrano.core` (`CyranoRestClientBuilder.applyTckListenerFallback`), `cyrano-tck`
  (`VaubanTckBootstrap`)
- **Symptom**: `CyranoRestClientBuilder.build()` held a branch keyed on the TCK's own class names: for the interface
  `org.eclipse.microprofile.rest.client.tck.interfaces.SimpleGetApi`, when no `RestClientListener` had been found, it
  registered `ReturnWith200RequestFilter` (or `ReturnWith500RequestFilter`) and called
  `SimpleRestClientListenerImpl.onNewClient` itself — the outcomes `RestClientBuilderListenerTest` and
  `RestClientListenerTest` check. Those two TCK tests passed without the listeners ever being discovered.
- **Minimal repro**: make `applyTckListenerFallback` return at once and run
  `-Ptck,tck-official -pl cyrano-tck -am verify -Dtest='RestClientBuilderListenerTest,RestClientListenerTest'`: both
  fail (`HTTP 500`; `The RestClientListener impl was not invoked expected [500] but found [200]`).
- **Root cause**: a harness gap hidden by a workaround in production code. The TCK deployments ship the listener and
  its `META-INF/services` file in a library jar (`WebArchive.addAsLibrary`); `VaubanTckBootstrap` put only the
  archive's loose resources on the deployment class loader, so `ServiceLoader` never saw the services file.
- **Investigations**:
  - 2026-10-08: found while fixing BUG-20261008-03. Fixed the harness: the deployment class loader also covers the
    archive's `WEB-INF/lib/*.jar`, as a servlet container would (classes still resolve parent-first). With that, both
    TCK tests pass with the fallback disabled; the fallback is removed. No other TCK class name remains in the
    production modules.

## BUG-20261008-05 — an asynchronous method can run its response filters on the caller's thread

- **Date**: 2026-10-08
- **Status**: FIXED (branch pr/ybl/rest-client-module-path — ships with the next release)
- **Affected module**: `io.vidocq.cyrano.core` (`CyranoInvocationHandler`, `CompletionStage` return path)
- **Symptom**: TCK `AsyncMethodTest.testInterfaceMethodWithCompletionStageObjectReturnIsInvokedAsynchronously` failed
  once on cyrano#24's CI (head fe24ec02): `AssertionError: did not expect [3] but found [3]` (AsyncMethodTest.java:143)
  — the response filter ran on the thread that called the client method. It passed locally on the same head.
- **Minimal repro**: an interface method returning `CompletionStage<String>`, a `ClientResponseFilter` that records its
  thread, no `executorService(...)` on the builder, and a response that is complete before `invoke` attaches its
  continuation (fast local server; deterministically: a transport whose `sendAsync` returns a completed future). Same
  with a request filter that calls `abortWith`.
- **Root cause**: with no executor configured, `asyncCallbackExecutor()` is `Runnable::run`. `sendAsync(...)
  .thenApplyAsync(fn, Runnable::run)` runs `fn` on whichever thread completes the future — or on the calling thread
  when the future is already complete. The `abortWith` path builds the result on the calling thread and returns
  `completedFuture`. Response filters, `MessageBodyReader`s, `ResponseExceptionMapper`s and
  `AsyncInvocationInterceptor.applyContext` then run on the caller's thread, which the TCK forbids for asynchronous
  methods.
- **Investigations**:
  - 2026-10-08: `CyranoInvocationHandlerTest` reproduces both paths deterministically (a transport whose `sendAsync`
    returns a completed future; a request filter calling `abortWith`): the response filter ran on `main`. Fixed: with no
    executor service configured, the response is processed on a new virtual thread, and the `abortWith` path completes
    through the same executor (`supplyAsync`) instead of returning `completedFuture`. With an executor service set on
    the builder, that executor is used, as before.

## BUG-20261008-06 — no @RestClient bean for an application's interface when the extension runs at build time

- **Date**: 2026-10-08
- **Status**: FIXED (branch pr/ybl/rest-client-module-path — ships with the next release)
- **Affected module**: `io.vidocq.cyrano.cdi.vauban` (`CyranoRestClientCdiExtension.synthesizeRestClientBeans`)
- **Symptom**: on the Vidocq runtime, `@Inject @RestClient GreetingClient` fails: at run time `DeploymentException:
  Unsatisfied dependency: field RelayResource.greetings of type ... GreetingClient with qualifiers [@Any, @RestClient]`
  when the extension is not on the annotation-processor path (the Vauban processor only warns that the BCE "will not
  run", and the runtime skips extensions for archives the processor already handled); with the extension on the
  processor path, the same message becomes a compilation error.
- **Minimal repro**: compile a `@RegisterRestClient` interface and a bean injecting it with `@Inject @RestClient`, with
  vauban-processor and cyrano-cdi-vauban on the processor path: `[Vauban] Unsatisfied dependency`.
- **Root cause**: the `@Enhancement` phase collects the interface (by its lang-model `ClassInfo`), but `@Synthesis`
  registers the bean with `components.addBean(Class)`, after loading the interface by name; at build time the interface
  is being compiled and cannot be loaded, so the extension skips it silently ("Vauban has already reported the
  classpath error", which it has not). No bean is synthesised, none is recorded for the runtime.
- **Investigations**:
  - 2026-10-08: reproduced on the Vidocq runtime with a Rest Client example (vidocq branch
    pr/ybl/rest-client-module-path): without the extension on the processor path the processor warns and the runtime
    fails with the unsatisfied dependency; with it, compilation fails the same way. Fixed: when the interface cannot be
    loaded, `@Synthesis` declares the bean with `addBean(Object.class).type(types.ofClass(info))`, the creator loading
    the interface by name when it builds the client. `CyranoRestClientCdiExtensionTest` (language-model doubles, an
    interface name no loader knows) saw no bean declared before the fix. The bean type then reaches the runtime only
    with vauban BUG-20261008-01 fixed (the synthetic metadata dropped language-model types). On Vidocq the extension
    reaches the processor through the new `vidocq-runtime-cyrano-rest-client-extension-codegen` bundle, which
    `vidocq:checkpom` now requires.
