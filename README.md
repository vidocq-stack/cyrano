# Cyrano

> Cyrano de Bergerac (1619–1655) spoke on behalf of others, lending them his eloquence
> to woo those they could not reach alone. That is exactly what a typed REST client does:
> it speaks on behalf of application code, lending its proxies and annotations to call
> remote services.

**MicroProfile Rest Client 4.0** implementation in the Vidocq style:
zero third-party libraries, JDK 25, virtual threads, strict JPMS, proxy generation
via the Class-File API (JEP 484), JDK `java.net.http.HttpClient` transport, CDI integration
via Vauban, JSON serialisation via Champollion (Jakarta JSON-B).

## Modules

| Module | Role |
|---|---|
| `cyrano-api` | Re-exports the `org.eclipse.microprofile.rest.client` spec + public SPI |
| `cyrano-core` | Standalone implementation: interface scanning, Class-File API proxy generation, JDK HttpClient transport, JSON-B mapping |
| `cyrano-cdi-vauban` | Vauban BCE discovering `@RegisterRestClient` interfaces |
| `cyrano-tck` | Official MicroProfile Rest Client 4.0 TCK runner (out-of-reactor) |

## Prerequisites

```bash
sdk env   # java=25-tem, maven=3.9.16
```

## Commands

```bash
# Full build (skip tests)
./mvnw -ntp install -DskipTests

# Unit tests
./mvnw test

# TCK smoke test
./run-official-tck-mp-rest-client-4.0.sh

# Full TCK suite
./run-official-tck-mp-rest-client-4.0.sh all

# Targeted TCK test
./run-official-tck-mp-rest-client-4.0.sh -Dtest=TestName
```

## Architecture

```
@Inject @RestClient MyService client
  → proxy Cyrano$MyService (Class-File API, JEP 484)
  → CyranoInvocationHandler
  → HttpRequest (java.net.http)
  → CyranoHttpTransport (virtual thread)
  → Jakarta JSON-B (champollion)
  → typed return value
```

See [`ROADMAP.md`](ROADMAP.md) for milestone status and [`CLAUDE.md`](CLAUDE.md)
/ [`AGENTS.md`](AGENTS.md) for contribution conventions.
