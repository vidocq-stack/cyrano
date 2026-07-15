# Cyrano <-> Cassini Integration

## Goal

Document a simple path to use Cyrano as a REST client against services
exposed by Cassini, with no third-party client dependency.

## Prerequisites

- Java 25
- Cyrano present in the client application
- HTTP service already exposed by Cassini

## Dependencies

Add the API module and the core implementation.

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

CDI option (Vauban):

```xml
<dependency>
  <groupId>io.vidocq.cyrano</groupId>
  <artifactId>cyrano-cdi-vauban</artifactId>
  <version>${cyrano.version}</version>
</dependency>
```

## Client interface example

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

## Programmatic usage

```java
import java.net.URI;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

UsersClient client = RestClientBuilder.newBuilder()
        .baseUri(URI.create("http://127.0.0.1:8080"))
        .build(UsersClient.class);

UserDto dto = client.findById(1L);
```

## CDI usage

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

Configure the base URL using the `configKey`:

```properties
users-api/mp-rest/url=http://127.0.0.1:8080
```

Or via the interface FQN:

```properties
com.acme.UsersClient/mp-rest/url=http://127.0.0.1:8080
```

## Java Modules notes

- `cyrano-core` stays standalone, with no CDI dependency.
- `cyrano-cdi-vauban` is optional.
- Proxies are generated via the Class-File API (JDK 25), not via `java.lang.reflect.Proxy`.
