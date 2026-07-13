# BUG — Cyrano

Tracking of reproducible bugs in `cyrano` (internal issues, regressions, incorrect behaviours
not yet fixed). Vidocq workspace convention: short id, date, symptom,
minimal repro, root cause hypothesis, status.

---

## CYR-001 — Java Modules bypassed via manual copy of compile-scope JARs

- **Opened**: 2026-05-25
- **Last revisited**: 2026-06-03
- **Status**: ⚠️ OPEN — narrowed workaround (1 module instead of the whole reactor)

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
