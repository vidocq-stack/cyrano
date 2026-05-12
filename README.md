# Cyrano

> Cyrano de Bergerac (1619–1655) parlait au nom des autres, leur prêtant son éloquence
> pour séduire qui ils ne pouvaient atteindre seuls. C'est exactement ce que fait un
> client REST typé : il parle au nom du code applicatif, lui prêtant ses proxies et
> ses annotations pour appeler les services distants.

Implémentation **MicroProfile Rest Client 4.0** dans le style Vidocq :
zéro librairie tierce, JDK 25, virtual threads, JPMS strict, génération de proxy
via Class-File API (JEP 484), transport JDK `java.net.http.HttpClient`, intégration
CDI via Vauban, sérialisation JSON via Champollion (Jakarta JSON-B).

## Modules

| Module | Rôle |
|---|---|
| `cyrano-api` | Re-expose la spec `org.eclipse.microprofile.rest.client` + SPI publique |
| `cyrano-core` | Implémentation standalone : scanning d'interfaces, génération de proxy Class-File API, transport JDK HttpClient, mapping JSON-B |
| `cyrano-cdi-vauban` | BCE Vauban découvrant les interfaces `@RegisterRestClient` |
| `cyrano-tck` | Runner TCK officiel MicroProfile Rest Client 4.0 (hors reactor) |

## Prérequis

```bash
sdk env   # java=25-tem, maven=4.0.0-rc-5
```

## Commandes

```bash
# Build complet (sans tests)
./mvnw -ntp install -DskipTests

# Tests unitaires
./mvnw test

# Smoke test TCK
./run-official-tck-mp-rest-client-4.0.sh

# Suite TCK complète
./run-official-tck-mp-rest-client-4.0.sh all

# Test TCK ciblé
./run-official-tck-mp-rest-client-4.0.sh -Dtest=NomDuTest
```

## Architecture

```
@Inject @RestClient MyService client
  → proxy Cyrano$MyService (Class-File API, JEP 484)
  → CyranoInvocationHandler
  → HttpRequest (java.net.http)
  → CyranoHttpTransport (virtual thread)
  → Jakarta JSON-B (champollion)
  → valeur de retour typée
```

Voir [`ROADMAP.md`](ROADMAP.md) pour l'état des milestones et [`CLAUDE.md`](CLAUDE.md)
/ [`AGENTS.md`](AGENTS.md) pour les conventions de contribution.

