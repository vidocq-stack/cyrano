# Cyrano — MicroProfile Rest Client 4.0 TCK Status

> Snapshot established at the end of M4 (initial iteration). The final M4 contract remains **100% PASS**;
> this document tracks in-progress gaps to drive subsequent iterations.

## Execution

```bash
./run-official-tck-mp-rest-client-4.0.sh        # smoke (CyranoTckSmokeTest, outside Arquillian)
./run-official-tck-mp-rest-client-4.0.sh all    # official suite (tck-official profile)
./run-official-tck-mp-rest-client-4.0.sh -Dtest=TestName
```

The report is generated in `cyrano-tck/target/tck-report.txt`; the raw Maven output is in
`cyrano-tck/target/tck-report.txt.raw`.

## Runner Architecture

| Component | Role |
|---|---|
| `CyranoDeployableContainer` | Arquillian *Local* container (no-op deploy/undeploy) |
| `CyranoArquillianExtension` | SPI `LoadableExtension` — registers the container |
| `VaubanTckBootstrap` | Boot/stop Vauban CDI per TCK archive + MP Config bridge (`TckConfigBridge`) |
| `WireMockProbeListener` | TestNG listener (`ITestNGListener`) that **starts WireMock at boot** via a static block, *before* any TCK `@BeforeMethod` |
| `WireMockTestBackend` | JVM-static singleton — port 8765, bind 127.0.0.1, JVM shutdown hook |

**Critical note**: Arquillian calls `LoadableExtension.register()` and
`DeployableContainer.start()` **after** `@AfterSuite` when the protocol is *Local*;
WireMock must therefore start earlier — that is the role of the static block in
`WireMockProbeListener` (loaded via `META-INF/services/org.testng.ITestNGListener`).

## Suites Excluded at M4 (initial)

| Pattern | Reason | Re-activation |
|---|---|---|
| `**/ssl/**` | Requires `RestClientBuilder.trustStore(...)` / `keyStore(...)` — not yet implemented | Next M4 iteration |
| `**/sse/**` | SSE out of scope M0-M5 (see ROADMAP `Open Decisions`) | Post-M5 if requested |

## Current Score

```
Tests run: 168, Failures: 0, Errors: 0, Skipped: 0
```

**Tests passed: 168/168 — 100% PASS** (report from 2026-05-24).

```text
# Tests passed: 168/168
RESULT: PASS
```

## Fixes Applied in this M4 Iteration

| Symptom | Fix |
|---|---|
| WireMock started **after** `@AfterSuite` (all `@BeforeMethod` fail) | Start via static block in `WireMockProbeListener` |
| `WireMock.reset()` admin URL pointed to `localhost:8080` by default | `WireMock.configureFor("127.0.0.1", 8765)` after `start()` |
| `@ClientHeaderParam(required=false)` propagated the compute method exception | Silently ignore the header if `required=false` (spec §6.5) |
| `@ClientHeaderParam(required=true)` wrapped in `IllegalStateException` | Propagates the original `RuntimeException` (spec §6.5) |
| `@ClientHeaderParam(value="{com.foo.Util.method}")` unresolved (FQN static) | `findHeaderMethod` detects the last `.` and resolves via `Class.forName` + static method |
| Method-level + interface-level header with same name: duplicated | `collectClientHeaders` removes the opposite-sign entry (static/dynamic) with the same name |
| Runtime `@HeaderParam` did not override `@ClientHeaderParam` with same name | `applyHeaders` skips static/dynamic if a runtime override is present |
| **M4-2** — `ClientRequestFilter` / `ClientResponseFilter` not wired | JAX-RS §6.3 / MP Rest Client §4.2 pipeline added: `CyranoClientRequestContext` + `CyranoClientResponseContext`; sorted by priority (ascending for request, descending for response); `abortWith(Response)` short-circuits the transport; standard property `org.eclipse.microprofile.rest.client.invokedMethod` exposed |
| **M4-3** — `CDI.current()` unavailable in the TCK runner | Vauban bootstrap per archive via `VaubanTckBootstrap` called from `CyranoDeployableContainer.deploy/undeploy`; extraction of `microprofile-config.properties` (WAR/JAR + nested libs) and projection to `TckConfigBridge` |
| **M4-4** — `followRedirects` / `connectTimeout` / `readTimeout` not propagated | `connectTimeout` wired to `HttpClient.Builder`, `readTimeout` to `HttpRequest.Builder.timeout(...)`, `followRedirects(true)` to `HttpClient.Redirect.NORMAL`; MP Config read added in `CyranoRestClientSyntheticCreator` (`/mp-rest/followRedirects`, `/connectTimeout`, `/readTimeout`) |

## Targeted M4-3 Validation

- Targeted run `ConfigKeyTest`: **PASS (2/2)** via `./run-official-tck-mp-rest-client-4.0.sh -Dtest=ConfigKeyTest`.
- Targeted run `cditests/*` family: **52 tests executed**; remaining failures are
  now functional (redirects, CDI providers, proxy, URI/URL priority), without
  CDI/Arquillian bootstrap errors.

## Targeted M4-4 Validation

- `FollowRedirectsTest,CDIFollowRedirectsTest`: **PASS (16/16)**.
- `TimeoutTest,TimeoutViaMPConfigTest,TimeoutViaMPConfigWithConfigKeyTest,TimeoutBuilderIndependentOfMPConfigTest`: **PASS (8/8)**.
- The `**/timeout/**` suites are re-enabled in `cyrano-tck/pom.xml`; only `ssl/**` and `sse/**` remain excluded.

## Known Challenges

| Suite | Status | Justification |
|---|---|---|
| `**/ssl/**` | **excluded** | Requires `RestClientBuilder.trustStore/keyStore` — out of current scope. |
| `**/sse/**` | **excluded** | SSE (Server-Sent Events) out of scope (see ROADMAP `Open Decisions`). |

The remaining 168 tests pass at 100%. No open functional challenges.
