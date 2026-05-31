# Integrating Cyrano in vidocq

## Objective

Enable Cyrano as the default REST client in a `vidocq` deployment
with a single extension dependency.

## Integration Strategy

1. Keep `cyrano-core` as the runtime client engine.
2. Add `cyrano-cdi-vauban` for `@Inject @RestClient` injection.
3. Expose an aggregate extension on the `vidocq` side to simplify adoption.

## Recommended Configuration

Configure clients via MP Config (Ravel):

```properties
# FQN variant
com.acme.api.UsersClient/mp-rest/url=http://users-service:8080

# configKey variant
users-api/mp-rest/url=http://users-service:8080
users-api/mp-rest/connectTimeout=500
users-api/mp-rest/readTimeout=2000
users-api/mp-rest/followRedirects=true
```

## Usage Contract

Client interfaces must be annotated:

```java
@RegisterRestClient(configKey = "users-api")
@Path("/users")
public interface UsersClient {
    @GET
    @Path("/{id}")
    UserDto findById(@PathParam("id") long id);
}
```

Then injected via CDI:

```java
@Inject
@RestClient
UsersClient usersClient;
```

## M5 → Implementation Mapping

- Operational documentation: this document + `docs/integration-cassini.md`.
- Architecture ADR: `docs/adr/ADR-001-classfile-proxy-strategy.md`.
- On the Cyrano side, the BCE `CyranoRestClientCdiExtension` is already published via
  `META-INF/services/...BuildCompatibleExtension` and `module-info.java provides ... with`.
- Wrapper module `vidocq-runtime-cyrano-extension`: to be implemented in the `vidocq` repo.

## Rollout Checklist

- Add the Cyrano extension to the vidocq BOM/packaging.
- Verify that `microprofile-config.properties` is loaded at runtime.
- Verify `@RestClient` injection on at least one business client.
- Run the Cyrano TCK suite before publishing the extension.
