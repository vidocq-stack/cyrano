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
- **Status**: OPEN
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
  - 2026-10-08: seen when moving the cyrano-processor and cyrano-cdi-vauban tests to the module path; their
    surefire `argLine` stands in for the read and the export until this is fixed.
