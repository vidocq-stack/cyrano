# Integration Cyrano dans vidocq-mps

## Objectif

Activer Cyrano comme client REST par defaut dans un deploiement `vidocq-mps`
avec une seule dependance d'extension.

## Strategie d'integration

1. Conserver `cyrano-core` comme moteur client runtime.
2. Ajouter `cyrano-cdi-vauban` pour l'injection `@Inject @RestClient`.
3. Exposer une extension agregee cote `vidocq-mps` pour simplifier l'adoption.

## Configuration recommandee

Configurer les clients via MP Config (Ravel) :

```properties
# Variante FQN
com.acme.api.UsersClient/mp-rest/url=http://users-service:8080

# Variante configKey
users-api/mp-rest/url=http://users-service:8080
users-api/mp-rest/connectTimeout=500
users-api/mp-rest/readTimeout=2000
users-api/mp-rest/followRedirects=true
```

## Contrat d'utilisation

Les interfaces clientes doivent etre annotees :

```java
@RegisterRestClient(configKey = "users-api")
@Path("/users")
public interface UsersClient {
    @GET
    @Path("/{id}")
    UserDto findById(@PathParam("id") long id);
}
```

Puis injectees via CDI :

```java
@Inject
@RestClient
UsersClient usersClient;
```

## Mapping M5 -> implementation

- Documentation operationnelle: ce document + `docs/integration-cassini.md`.
- ADR architecture: `docs/adr/ADR-001-classfile-proxy-strategy.md`.
- Cote Cyrano, la BCE `CyranoRestClientCdiExtension` est deja publiee via
  `META-INF/services/...BuildCompatibleExtension` et `module-info.java provides ... with`.
- Module wrapper `vidocq-mps-cyrano-extension`: a implementer dans le depot `vidocq-mps`.

## Checklist de rollout

- Ajouter l'extension Cyrano dans le BOM/packaging `vidocq-mps`.
- Verifier que `microprofile-config.properties` est charge en execution.
- Verifier l'injection `@RestClient` sur au moins un client metier.
- Executer la suite TCK Cyrano avant publication de l'extension.

