# Integration Cyrano <-> Cassini

## But

Documenter un chemin simple pour utiliser Cyrano comme client REST vers des services
exposes par Cassini, sans dependance cliente tierce.

## Prerequis

- Java 25
- Cyrano present dans l'application cliente
- Service HTTP deja expose par Cassini

## Dependances

Ajouter le module API et l'implementation core.

```xml
<dependency>
  <groupId>io.vidocq.cyrano</groupId>
  <artifactId>cyrano-api</artifactId>
  <version>${cyrano.version}</version>
</dependency>
<dependency>
  <groupId>io.vidocq.cyrano</groupId>
  <artifactId>cyrano-core</artifactId>
  <version>${cyrano.version}</version>
</dependency>
```

Option CDI (Vauban) :

```xml
<dependency>
  <groupId>io.vidocq.cyrano</groupId>
  <artifactId>cyrano-cdi-vauban</artifactId>
  <version>${cyrano.version}</version>
</dependency>
```

## Exemple d'interface client

```java
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "users-api")
@Path("/users")
public interface UsersClient {
    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    UserDto findById(@PathParam("id") long id);
}
```

## Utilisation programmatique

```java
import java.net.URI;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

UsersClient client = RestClientBuilder.newBuilder()
        .baseUri(URI.create("http://127.0.0.1:8080"))
        .build(UsersClient.class);

UserDto dto = client.findById(1L);
```

## Utilisation CDI

```java
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

class UserService {
    @Inject
    @RestClient
    UsersClient usersClient;
}
```

## Base URL via MP Config (Ravel)

Configurer la base URL avec le `configKey` :

```properties
users-api/mp-rest/url=http://127.0.0.1:8080
```

Ou via FQN de l'interface :

```properties
com.acme.UsersClient/mp-rest/url=http://127.0.0.1:8080
```

## Notes JPMS

- `cyrano-core` reste standalone, sans dependance CDI.
- `cyrano-cdi-vauban` est optionnel.
- Les proxies sont generes via Class-File API JDK 25, pas via `java.lang.reflect.Proxy`.
