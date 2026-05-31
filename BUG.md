# BUG — Cyrano

Tracking of reproducible bugs in `cyrano` (internal issues, regressions, incorrect behaviours
not yet fixed). Vidocq workspace convention: short id, date, symptom,
minimal repro, root cause hypothesis, status.

---

## CYR-001 — JPMS bypassed via manual copy of compile-scope JARs

- **Opened**: 2026-05-25
- **Status**: ⚠️ OPEN — active workaround

### Symptom

The root `pom.xml` of cyrano uses `maven-dependency-plugin` (phase `initialize`) to
copy all compile-scope JARs into `target/javamodules/`, then passes
`--module-path ${project.build.directory}/javamodules` manually to the compiler.

This workaround indicates that Maven's native JPMS resolution does not work for
certain compile-scope dependencies of cyrano, notably `microprofile-rest-client-api`,
`vauban-core`/`vauban-classloader-spi`, `champollion-jsonp`/`champollion-jsonb`.

### Minimal Repro

```bash
grep -n "javamodules\|module-path" cyrano/pom.xml
# reveals the two manually configured plugins
```

Without the workaround (removing the `maven-dependency-plugin` config), `javac` fails with:

```
error: module not found: org.eclipse.microprofile.rest.client
```

### Root Cause Hypothesis

The affected JARs do not have a proper `module-info.class` — they only expose an
`Automatic-Module-Name` in their `MANIFEST.MF`. Version 4.x of `maven-compiler-plugin`
does not automatically place them on `--module-path` for projects with an explicit
`module-info.java`. Copying to `target/javamodules/` allows javac to resolve them
as automatic modules by deriving their name from the JAR filename.

### Resolution Path

1. Check whether an upstream version of `microprofile-rest-client-api` publishes a
   `module-info.class`. If so, bump the version and remove the workaround.
2. Contact / PR upstream Eclipse MicroProfile to add a modular descriptor.
3. Otherwise, wrap via an internal Cyrano module (`cyrano-mp-rest-client-api`) that provides
   the missing `module-info.class` — a pattern already used for `ravel-mp-config-api`.
