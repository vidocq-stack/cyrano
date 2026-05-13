# Cyrano — Plan d'attaque

> Implémentation MicroProfile Rest Client 4.0 dans le style Vidocq : zéro librairie tierce
> (specs Jakarta EE / MicroProfile autorisées), JDK 25, virtual threads, JPMS strict,
> génération de proxy via Class-File API (JEP 484), transport JDK `java.net.http.HttpClient`,
> intégration CDI optionnelle via Vauban.

## Principes directeurs

| Principe | Application concrète |
|---|---|
| Zéro librairie tierce | Pas de RESTEasy Client, CXF, Jersey Client, OkHttp dans `cyrano-core`. Seules les API specs compilées : `microprofile-rest-client-api` + `jakarta.ws.rs` + `jakarta.json.bind`. Transport : `java.net.http.HttpClient` (JDK pur). |
| Specs Jakarta / MicroProfile autorisées | `cyrano-cdi-vauban` peut dépendre de `jakarta.enterprise.cdi-api`, `jakarta.inject-api`, `jakarta.annotation-api`. `cyrano-core` se limite à `jakarta.ws.rs` + `jakarta.json.bind`. |
| Class-File API (JEP 484) | Pas de `java.lang.reflect.Proxy`. Générer de vraies classes nommées `Cyrano$<Interface>` via l'API `ClassFile` du JDK 25. Mise en cache dans `CyranoProxyCache` (ConcurrentHashMap, lazy-init). Compatible GraalVM, Leyden CDS, stack traces lisibles. |
| Virtual threads | Pas de `synchronized`, pas de `ThreadLocal`. `HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor())`. `CompletionStage<T>` géré via `HttpClient.sendAsync` sur virtual threads. |
| JPMS strict | `module-info.java` partout, packages `internal.*` non exportés, SPI via `provides/uses`. Pas d'`opens` non justifié. |
| TDD strict | Red → Green → Refactor. Tests écrits avant le code de prod. Citation systématique de la section spec MicroProfile Rest Client 4.0 dans le JavaDoc des tests. |
| TCK PASS 100 % | Contrat dur sur MicroProfile Rest Client 4.0 TCK avant tout merge structurel. |
| AOT-friendly | Pas de proxy dynamique `java.lang.reflect.Proxy`, pas de `setAccessible(true)`. Classes nommées générées via Class-File API → référençables dans les configs GraalVM `reflect-config.json` (mais l'objectif est d'éviter toute config AOT manuelle). |

## Méthodologie : TDD + TCK comme garde-fous parallèles

Cyrano est développé en **TDD strict** (Red → Green → Refactor). Aucune ligne de production
n'est écrite avant un test qui la justifie. Au-delà du cycle TDD interne :

- **Couche 1 — tests unitaires TDD** : pilotent la conception de chaque classe.
- **Couche 2 — tests d'intégration `cyrano-core`** : scénarios client complets avec serveur
  mock JDK inline (`com.sun.net.httpserver.HttpServer`), multi-méthodes HTTP, sérialisation JSON.
- **Couche 3 — TCK officiel** (`microprofile-rest-client-tck:4.0`) : contrat 100 % PASS avant
  tout merge structurel. Module hors reactor (POM Model 4.0.0).

## Rappel spec MicroProfile Rest Client 4.0 — points clés

### Interface client (§3)

```java
@RegisterRestClient(baseUri = "https://api.example.com")
@Path("/users")
public interface UserService {
    @GET @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    User getUser(@PathParam("id") long id);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createUser(User user);

    @GET
    CompletionStage<List<User>> listUsersAsync();
}
```

### Configuration (§5)
- Base URI : `@RegisterRestClient(baseUri=...)` ou `<interface.fqn>/mp-rest/url` via MP Config.
- Providers enregistrables : `ResponseExceptionMapper`, `ClientRequestFilter`, `ClientResponseFilter`,
  `MessageBodyReader`, `MessageBodyWriter`, `ParamConverter`.

### CDI (§6)
- `@RegisterRestClient` sur l'interface → bean CDI qualifié `@RestClient` + `@Default`.
- `@Inject @RestClient UserService client` → proxy Cyrano injecté.
- Portée CDI par défaut : `@Dependent` (spec §6.3) ; surchargeables via `@RegisterProvider`.

### Exception mapping (§7 + §8)
- `ResponseExceptionMapper<E extends Throwable>` : converti les réponses HTTP en exceptions.
- Default mapper : toute réponse HTTP ≥ 400 → `WebApplicationException` (§8, priorité 1).

### Async (§9)
- Retour `CompletionStage<T>` : appel non-bloquant via `HttpClient.sendAsync`.
- Virtual threads : `CompletionStage` complété sur un virtual thread.

### Paramètres (§3 + §4)
- `@PathParam`, `@QueryParam`, `@HeaderParam`, `@CookieParam`, `@FormParam`, `@MatrixParam`
- `@BeanParam` : aggrège plusieurs paramètres dans un objet
- `@ClientHeaderParam` : headers statiques ou dynamiques (méthode de calcul)
- `@DefaultValue` : valeur par défaut si le paramètre est absent

---

## Phases

### M0 — Bootstrap

- [x] `.sdkmanrc` (`java=25-tem`, `maven=4.0.0-rc-5`)
- [x] `.gitignore`, `.mvn/maven.config`
- [x] `pom.xml` parent (Model 4.1.0, multi-module, dependency management Jakarta + MicroProfile Rest Client)
- [x] `CLAUDE.md`, `AGENTS.md`, `ROADMAP.md` (ce fichier) ✅
- [x] Validation JPMS : `microprofile-rest-client-api:4.0` n'a **ni** `Automatic-Module-Name`
      **ni** `module-info.class`. Adoption du même patron que `knock` : nom JPMS dérivé
      `microprofile.rest.client.api` (strip version + remplacement `-`/`.`), JAR forcé sur
      le module-path via `target/javamodules/` (`maven-dependency-plugin` phase
      `initialize`). Aucun module de repackage requis.
- [x] Création des modules avec `pom.xml` + `module-info.java` squelettes :
      `cyrano-api`, `cyrano-core`, `cyrano-cdi-vauban` + `cyrano-tck` (hors reactor)
- [x] `run-official-tck-mp-rest-client-4.0.sh`
- [x] Validation `./mvnw -ntp install -DskipTests` réussit (reactor + cyrano-tck standalone)
- [x] Smoke test `CyranoTckSmokeTest` : 3 tests PASS

**Livrable :** build vert, JPMS strict validé sur tous les modules squelettes, smoke TCK compilable. ✅

---

### M1 — Core : scanning d'interface + proxy Class-File API + transport JDK

**Scope spec :** §3 (interface client), §3.1 (types de paramètre de base), §4 (invocation).

| Tâche | Notes | État |
|---|---|---|
| `CyranoRestClientBuilder` implémente `RestClientBuilder` | `baseUri(URI)`, `build(Class<T>)`, `register(Class<?>)` | [x] |
| `CyranoInterfaceScanner` | Scanne les annotations JAX-RS sur l'interface + méthodes : `@Path`, verbes HTTP, `@PathParam`, `@QueryParam` | [x] |
| `RequestSpec` record | Capture la méthode HTTP, le path template, les bindings de paramètres — immuable, construit par l'interface scanner | [x] |
| `CyranoProxyGenerator` — Class-File API (JEP 484) | Génère une classe nommée `Cyrano$<SimpleName>` implémentant l'interface dans le **package de l'interface** (`privateLookupIn`). Chaque méthode délègue à `CyranoInvocationHandler.invoke(methodIndex, args)`. Aucun `java.lang.reflect.Proxy`. | [x] |
| `CyranoProxyCache` | `ConcurrentHashMap<Class<?>, Entry>`, `computeIfAbsent`, thread-safe. | [x] |
| `CyranoInvocationHandler` | Résout les paramètres de path/query, construit `HttpRequest`, délègue au transport. | [x] |
| `CyranoHttpTransport` | `java.net.http.HttpClient` + `VirtualThreadPerTaskExecutor`. `send()` (sync) et `sendAsync()` (async). | [x] |
| Types de retour : `String`, `Response`, primitifs | Mapping réponse HTTP → valeur de retour (Response via `CyranoLightResponse` minimal — M1) | [x] |
| Tests unitaires TDD `CyranoInterfaceScannerTest` | 6 tests — couverture spec §3 / §3.1 | [x] |
| Tests unitaires TDD `CyranoProxyGeneratorTest` | 4 tests — vérifie nom de classe, implémentation d'interface, délégation au handler | [x] |
| Tests d'intégration avec `com.sun.net.httpserver.HttpServer` JDK | 7 tests `CyranoEndToEndTest` — GET path-param, GET query-param, POST, retour primitif, cache de proxy | [x] |
| SPI `RestClientBuilderResolver` + `META-INF/services` + `provides` JPMS | `CyranoRestClientBuilderResolver` exporté via `io.vidocq.cyrano.runtime` | [x] |

**Décisions M1 :**
- `ClassFile.of().build(...)` pour générer le bytecode ; `MethodHandles.privateLookupIn(iface, ...)`
  + `defineClass(bytes)` pour le charger dans **le module de l'interface utilisateur** (et non
  cyrano-core), ce qui permet d'implémenter des interfaces package-private et évite les
  problèmes de visibilité inter-modules. Conséquence : les modules clients devront ouvrir
  leur package à `io.vidocq.cyrano.core` (sera documenté à M3).
- `CyranoInvocationHandler.invoke(int methodIndex, Object[] args)` : signature stable appelée
  par tout proxy généré ; non final pour permettre du stubbing en test (TDD sans Mockito).
- `HttpClient` configuré avec HTTP/2 (fallback HTTP/1.1 auto) + `VirtualThreadPerTaskExecutor`.
- `Response` retour : `CyranoLightResponse` minimal en M1 ; sera remplacé par l'usage de
  `RuntimeDelegate` (Cassini) en M2.

**Livrable :** `RestClientBuilder.newBuilder().baseUri(uri).build(MyService.class).getUser(1L)`
effectue un vrai appel HTTP GET et retourne la réponse — validé par 17 tests unitaires + 5
tests smoke TCK. ✅

---

### M2 — Request/Response mapping complet

**Scope spec :** §3.1 (tous les types de paramètre), §4.2 (MessageBody), §5 (providers).

| Tâche | Notes | État |
|---|---|---|
| `@HeaderParam`, `@CookieParam`, `@FormParam`, `@MatrixParam` | Binding paramètre → header/cookie/form/matrix HTTP | [x] |
| `@BeanParam` | Aggrège plusieurs annotations de paramètre dans un POJO (champs annotés `@PathParam`/`@QueryParam`/`@HeaderParam`/`@CookieParam`/`@FormParam`/`@MatrixParam`) | [x] |
| `@ClientHeaderParam` (statique et dynamique) | Header statique (valeurs littérales) ou dynamique (`{methodName}` → méthode `default` de l'interface, signature `()` ou `(String)`) — au niveau type ou méthode | [x] |
| `@DefaultValue` | Valeur par défaut si paramètre `null` (paramètre ou champ `@BeanParam`) | [x] |
| `@Consumes` / `@Produces` | Injection header `Content-Type` (depuis `@Consumes`) et `Accept` (depuis `@Produces`) | [x] |
| Sérialisation corps : Jakarta JSON-B | `Jsonb.toJson(body)` pour les corps de requête POJO (paramètre non annoté) | [x] |
| Désérialisation corps : Jakarta JSON-B | `Jsonb.fromJson(responseBody, returnType)` pour les retours POJO + génériques | [x] |
| `Optional<T>` retour | 404 → `Optional.empty()`, 200 → `Optional.of(deserializedValue)` | [x] |
| `List<T>`, `Set<T>`, `Map<K,V>` retour | Désérialisation tableau/objet JSON → collection Java | [x] |
| `ResponseExceptionMapper<E>` SPI | Enregistrable via `RestClientBuilder.register(...)` — appliqué dans l'ordre de priorité | [x] |
| Default exception mapper (§8) | HTTP ≥ 400 → `WebApplicationException` (priorité 1) construit avec `CyranoLightResponse` | [x] |
| Tests TDD param binding | 17 tests `CyranoMappingM2Test` — un test par type de paramètre + cas `@DefaultValue` + `@BeanParam` | [x] |
| Tests TDD JSON serialization | POST POJO sérialisé, GET POJO désérialisé, `List`/`Set`/`Optional` retour | [x] |

**Décisions M2 :**
- `ParamBinding` sealed étendu : `Path`, `Query`, `Header`, `Cookie`, `Form`, `Matrix`, `Body`,
  `Bean(List<FieldBinding>)` — résolution exhaustive via `switch` (pattern matching JDK 25).
- Le bytecode généré passe désormais `this` (le proxy) au handler en plus du `methodIndex` et
  des `args` — nécessaire pour invoquer les méthodes `default` référencées par
  `@ClientHeaderParam(value="{methodName}")`. Signature : `handler.invoke(Object, int, Object[])`.
- Champollion (`jakarta.json.bind.Jsonb`) est l'unique sérialiseur ; `cyrano-core` déclare uniquement
  l'API `jakarta.json.bind` — `champollion-jsonb` + `champollion-jsonp` sont en scope `runtime`
  (test scope ici pour cyrano-core, runtime scope au déploiement final).
- `ResponseExceptionMapper` appliqués dans l'ordre de priorité croissante (priorité basse =
  préséance haute) ; le default mapper est implicite, déclenché si aucun user mapper ne `handles()`.
- Form-encoded vs JSON body : si un seul `@FormParam` est présent → `application/x-www-form-urlencoded`,
  sinon si paramètre non annoté présent → JSON-B avec `Content-Type` issu de `@Consumes` ou `application/json`.

**Livrable :** POJO sérialisé/désérialisé correctement ; headers/cookies/form envoyés ;
exception mapping fonctionnel pour les codes HTTP d'erreur. Validé par **34/34 tests** dans
`cyrano-core` (6 scanner + 4 proxy generator + 7 end-to-end M1 + 17 mapping M2). ✅

---

### M3 — Intégration CDI Vauban (`cyrano-cdi-vauban`)

**Scope spec :** §6 (CDI integration), §6.1 (discovery), §6.2 (injection), §6.3 (scope), §6.4 (config).

| Tâche | Notes | État |
|---|---|---|
| BCE `CyranoRestClientExtension` | `@Enhancement(types=Object.class, withAnnotations=RegisterRestClient.class)` — détecte les interfaces annotées `@RegisterRestClient` (cf. `CyranoRestClientCdiExtension`) | [x] |
| Producer CDI par interface découverte | `SyntheticBean` via `@Synthesis` + `CyranoRestClientSyntheticCreator` qui appelle `RestClientBuilder.newBuilder().baseUri(uri).build(iface)` | [x] |
| `@RestClient` qualifier | Bean synthétique qualifié `org.eclipse.microprofile.rest.client.inject.RestClient` (spec §6.2) | [x] |
| Base URI depuis `@RegisterRestClient(baseUri=...)` | Priorité basse : valeur d'annotation, résolue par `CyranoBaseUriResolver` | [x] |
| Base URI depuis MicroProfile Config (Ravel) | `<interface.fqn>/mp-rest/url` (priorité 1) puis `<configKey>/mp-rest/url` (priorité 2) — détection réflexive de `ConfigProvider` pour rester optionnel | [x] |
| Scope CDI par défaut `@Dependent` (§6.3) | Surchargeable via `@ApplicationScoped` / `@RequestScoped` / `@SessionScoped` / `@Singleton` portée par l'interface | [x] |
| Validation au déploiement | Interface @RegisterRestClient sans base URI ni config → `IllegalStateException` (à mapper en `DeploymentException` runtime, §M3 itération suivante si TCK requis) | [x] |
| Tests d'intégration avec Vauban embedded | `@Inject @RestClient PingApi client` → proxy injecté + appel HTTP réel sur `HttpServer` JDK inline (2 tests `CyranoRestClientCdiIntegrationTest` + 7 unit `CyranoBaseUriResolverTest`) | [x] |

**Décisions M3 :**
- MP Config (Ravel) résolu **par réflexion** sur `org.eclipse.microprofile.config.ConfigProvider` —
  cyrano-cdi-vauban ne déclare aucune dépendance compile sur `microprofile-config-api`. Si
  l'API n'est pas chargeable, `CyranoBaseUriResolver.defaultMpConfigLookup()` retourne une
  fonction `key → Optional.empty()` (dégradation gracieuse demandée par AGENTS.md).
- Le **BCE pipeline** utilise `@Enhancement` (CDI Lite §3.8) pour observer les interfaces
  annotées sans en faire des beans gérés, puis `@Synthesis` pour enregistrer un
  `SyntheticBean` par interface. Le `SyntheticBeanCreator` reçoit les valeurs littérales
  de l'annotation via `withParam(...)` et résout la base URI au moment de l'instanciation
  du bean (runtime, pas build-time — permet l'override MP Config sans rebuild).
- **Discovery Vauban** : la BCE est publiée à la fois via `META-INF/services/...BuildCompatibleExtension`
  (classpath) et via `provides ... with` dans `module-info.java` (JPMS strict). Un fichier
  `META-INF/vauban-beans.list` complète pour les environnements jlink/JPMS où le ServiceLoader
  classpath n'est pas suffisant — même patron que ravel-cdi-vauban.
- **Workaround compile-module-info** : `exports io.vidocq.cyrano.cdi` retiré car le package
  ne contient que `package-info.java` que la phase `prepare-package`/`compile-module-info`
  ne voit pas dans `target/classes` (incompatible avec maven-compiler-plugin 4.0.0-beta-4 sur
  une compilation incrémentale séparée). Seul `io.vidocq.cyrano.cdi.internal` est exporté ;
  ce package pourra être promu en SPI publique stable au besoin en M5.

**Livrable :** `@Inject @RestClient UserService client` injecté par Vauban et fonctionnel
en test d'intégration avec serveur mock JDK inline. Validé par **9/9 tests** dans
`cyrano-cdi-vauban` (7 unit `CyranoBaseUriResolverTest` + 2 intégration
`CyranoRestClientCdiIntegrationTest`). ✅

---

### M4 — TCK MicroProfile Rest Client 4.0

**Scope :** validation officielle MicroProfile Rest Client 4.0 + script reproductible.

| Tâche | Notes | État |
|---|---|---|
| `cyrano-tck/pom.xml` Model 4.0.0 standalone | Idem `cassini-tck`/`knock-tck`/`ravel-tck` — hors reactor | [x] |
| Runner Arquillian + harness officiel `microprofile-rest-client-tck:4.0` | `CyranoDeployableContainer` (protocole Arquillian *Local*) + `CyranoArquillianExtension` + `WireMockProbeListener` (boot WireMock via bloc statique) | [x] |
| Backend mock TCK : WireMock 3.10 embedded | WireMock 127.0.0.1:8765 — singleton JVM-static démarré au chargement du listener TestNG (avant tout `@BeforeMethod`) | [x] |
| Configuration Cyrano → backend mock | Le TCK injecte lui-même l'URI cible via `RestClientBuilder.baseUri(getServerURI())` et `WireMock.configureFor("127.0.0.1", 8765)` | [x] |
| `run-official-tck-mp-rest-client-4.0.sh` | Modes : smoke / all / `-Dtest=NomTest` ; rapport `target/tck-report.txt` | [x] |
| Itération M4-1 : fixes initiaux (M4-1 baseline) | `@ClientHeaderParam(required)` ; FQN static compute ; override interface↔method ; propagation exception originale ; voir `TCK.md` | [x] |
| Itération M4-2 : `ClientRequestFilter` / `ClientResponseFilter` SPI | Pipeline JAX-RS de filters côté client : `CyranoClientRequestContext` + `CyranoClientResponseContext` + tri par priorité (ascendant requête / descendant réponse) ; `abortWith(Response)` court-circuite le transport ; propriété `org.eclipse.microprofile.rest.client.invokedMethod` exposée ; +12 tests TCK PASS | [x] |
| Itération M4-3 : CDI bootstrap dans le runner | Vauban embarqué pour `CDI.current()` (débloque ~25 tests `cditests/*`) | [x] |
| Itération M4-4 : `connectTimeout` / `readTimeout` / `followRedirects` | Options propagées à `HttpClient`, réactiver `**/timeout/**` | [x] |
| Itération M4-5 : `QueryParamStyle` + `@EntityPart` + héritage interface + `Feature` SPI | Encodage URL paramétrable, multipart, scan récursif, providers | [x] |
| **Score contrat : 100 % PASS** | M4-1 baseline : ~26 PASS / 160 (hors SSE/SSL/timeout) ≈ 16 %. Projection M4-2..M4-5 : ≥ 80 % | [ ] |

**Architecture du `CyranoDeployableContainer` :**
- Container Arquillian *Local* — no-op deploy/undeploy : Cyrano est un client, aucune ressource JAX-RS serveur à déployer.
- WireMock joue le rôle de backend HTTP (la spec TCK 4.0 s'appuie dessus via `WiremockArquillianTest`).
- `WireMockProbeListener` (TestNG `ITestNGListener`) démarre WireMock dès le chargement du runner — *avant* `@BeforeMethod`. Le binding Arquillian (`LoadableExtension.register()`, `DeployableContainer.start()`) s'est révélé trop tardif (appelé après `@AfterSuite` pour le protocole *Local*).

**Décisions M4-2 — pipeline filters (livré) :**
- `CyranoClientRequestContext` et `CyranoClientResponseContext` exposent l'API JAX-RS §6.3
  sans embarquer d'impl JAX-RS server-side : URI, méthode, headers (`MultivaluedMap`),
  entity (POJO non sérialisé), properties, `abortWith`. La sérialisation JSON-B est
  différée à `buildHttpRequest()`, **après** le pipeline filters — un filtre peut donc
  modifier l'entity avant transport.
- Tri par priorité : ascendant pour les request filters (la priorité la plus basse =
  la plus prioritaire, s'exécute en premier — `Priorities.AUTHENTICATION = 1000`),
  descendant pour les response filters (sens inverse, conforme à JAX-RS §6.3).
  Priorité issue dans cet ordre : (1) map de contracts passée à `register(...)`,
  (2) `@Priority` (détecté réflectivement pour éviter une dépendance compile-time
  sur `jakarta.annotation-api`), (3) `Priorities.USER = 5000`.
- `abortWith(Response)` court-circuite le transport : la `CyranoClientResponseContext`
  est dérivée de la `Response` abortée (sans appeler `HttpClient`), puis les response
  filters s'exécutent normalement.
- Propriété standard `org.eclipse.microprofile.rest.client.invokedMethod` (MP Rest
  Client §4.2) injectée sur le contexte avant les filtres — pointée par
  `InvokedMethodRequestFilter` du TCK.

**Décisions M4-3 — bootstrap CDI runner (livré) :**
- Le container Arquillian *Local* démarre désormais un container Vauban par archive
  de test via `VaubanTckBootstrap.deploy(Archive)` et le ferme via
  `VaubanTckBootstrap.undeploy()` (cycle `deploy/undeploy` de `CyranoDeployableContainer`).
- `META-INF/microprofile-config.properties` est extrait de l'archive ShrinkWrap
  (WAR/JAR, y compris `WEB-INF/lib/*.jar`) puis projeté dans `TckConfigBridge`
  pour garantir des valeurs MP Config cohérentes côté `CDI.current()`.
- Les classes de l'archive (interfaces incluses) sont injectées au bootstrap Vauban
  pour permettre à la BCE Cyrano (`@RegisterRestClient`) de découvrir les clients
  CDI exactement comme dans le packaging TCK officiel.

**Décisions M4-4 — timeouts + redirects (livré) :**
- `connectTimeout` est appliqué au `HttpClient.Builder` JDK (`connectTimeout(Duration)`),
  tandis que `readTimeout` est appliqué par requête via `HttpRequest.Builder.timeout(Duration)`
  — ce découpage épouse exactement le modèle de `java.net.http`.
- `followRedirects(false)` devient `HttpClient.Redirect.NEVER` (valeur par défaut), et
  `followRedirects(true)` devient `HttpClient.Redirect.NORMAL` ; les tests redirects
  301/302/303/307 passent désormais aussi bien en programmatique qu'en CDI.
- Côté CDI, `CyranoRestClientSyntheticCreator` lit désormais les propriétés MP Config
  `<fqn>/mp-rest/followRedirects`, `<fqn>/mp-rest/connectTimeout`,
  `<fqn>/mp-rest/readTimeout` avec fallback `<configKey>/...` et les applique au
  `RestClientBuilder` avant `build(...)`.
- Les réponses HTTP exposent désormais leurs headers de façon **insensible à la casse**
  (`Location`, `Content-Type`, etc.) dans `CyranoClientResponseContext` et
  `CyranoLightResponse`, ce qui aligne Cyrano sur la sémantique HTTP attendue par le TCK.

**Suites désactivées à M4-1** (cf. `TCK.md`) :
- `**/ssl/**` : requiert `RestClientBuilder.trustStore(...)` non implémenté.
- `**/timeout/**` : requiert `connectTimeout`/`readTimeout` non implémentés.
- `**/sse/**` : SSE hors scope M0-M5 (cf. *Décisions ouvertes*).

**Livrable M4-1 :** runner Arquillian + WireMock fonctionnel, sous-ensemble représentatif
de la spec couvert ; gap analysis dans `TCK.md`. Livrable M4 final : TCK
MicroProfile Rest Client 4.0 **100 % PASS** via `./run-official-tck-mp-rest-client-4.0.sh all`.

---

### M5 — Intégration écosystème Vidocq

**Scope :** intégrer Cyrano dans `vidocq-mps` comme client REST par défaut.

| Tâche | Notes | État |
|---|---|---|
| Documentation `docs/integration-cassini.md` | Utiliser Cyrano pour appeler des services Cassini externes | [x] |
| Documentation `docs/integration-vidocq-mps.md` | Configuration Cyrano dans vidocq-mps, base URL via Ravel | [x] |
| ADR-001 stratégie génération proxy (Class-File API vs réflexion) | Rationale, AOT, jlink, GraalVM | [x] |
| Module wrapper `vidocq-mps-cyrano-extension` dans `vidocq-mps` | Active Cyrano via une seule dépendance, sans code Java additionnel — à livrer dans le dépôt `vidocq-mps` | [ ] |
| ServiceLoader BCE (`META-INF/services/...BuildCompatibleExtension`) | `CyranoRestClientCdiExtension` exposée via le contrat CDI 4.1 standard, couverte par `CyranoRestClientCdiExtensionDiscoveryTest` | [x] |
| `module-info.java` `provides ... with` | JPMS pour les fichiers de services, validé par le test de découverte BCE | [x] |

**Livrable :** documentation complète, module wrapper installable, Cyrano disponible dans
tout déploiement vidocq-mps via une seule dépendance.

**État réel côté dépôt Cyrano :** M5 est terminé pour le périmètre présent ici (documentation,
exposition ServiceLoader + JPMS, test de non-régression). Le seul élément restant vit dans le
dépôt externe `vidocq-mps` : le module wrapper d'agrégation.

---

## Ordre de priorité — pourquoi celui-ci ?

1. **M1 (proxy + transport)** d'abord : le moteur de génération Class-File API et le transport
   JDK sont le cœur. Rien d'autre ne peut être testé sans eux.
2. **M2 (mapping)** avant M3 (CDI) : les bindings de paramètres et la sérialisation JSON
   sont utilisés à la fois en mode programmatique et CDI. Valider le core avant d'y brancher
   un container CDI.
3. **M3 (CDI)** : module optionnel. Peut être développé en parallèle de M2 une fois le
   `CyranoRestClientBuilder` stabilisé.
4. **M4 (TCK)** : contrat de conformité. Le TCK est exécuté à chaque milestone pour vérifier
   les sections couvertes ; 100 % PASS verrouillé avant M5.
5. **M5 (intégration)** en dernier : on ne pollue pas les autres projets Vidocq avant que
   Cyrano soit TCK-validé.

## Risques connus

| Risque | Mitigation |
|---|---|
| `microprofile-rest-client-api:4.0` sans `Automatic-Module-Name` ni `module-info.class` | Créer `cyrano-mp-rest-client-api` de repackage (même patron que `ravel-mp-config-api`) ; valider dès M0 |
| Class-File API JEP 484 : API instable ou breaking change entre EA builds JDK 25 | Encapsuler dans `CyranoProxyGenerator` ; pin JDK 25 GA via sdkmanrc ; tests sur chaque EA build en CI |
| TCK nécessite un container CDI complet (Weld) que Vauban ne supporte pas | Évaluer ce que le TCK exige réellement ; si Weld inévitable, isoler en test-scope only (jamais en prod scope) ; documenter dans TCK.md |
| Incompatibilité ShrinkWrap Maven Resolver 3.3 vs JDK 25 (modules non reconnus) | Même résolution que knock-tck : classpath séparé pour ShrinkWrap dans le runner |
| `@BeanParam` : complexité de résolution récursive des sous-annotations | Implémenter en M2 avec tests exhaustifs avant de toucher au TCK |
| Gestion des multipart/form-data (optionnel spec 4.0) | Vérifier si le TCK en a besoin ; implémenter en M2 si oui ; sinon reporter |
| Config Ravel absente du module-path en test | `cyrano-cdi-vauban` doit dégrader gracieusement (pas de NPE) si Ravel absent |
| Async `CompletionStage<T>` et annulation | Propager `HttpRequest.cancel()` depuis le `CompletableFuture` retourné |

## Décisions actées

- ✅ **Class-File API (JEP 484)** pour la génération de proxy — pas de `java.lang.reflect.Proxy`
- ✅ **`java.net.http.HttpClient`** comme transport — zero-dep, virtual thread executor natif
- ✅ **champollion** comme seule implémentation Jakarta JSON-B runtime
- ✅ **`cyrano-core` standalone SE** : utilisable sans CDI, sans container
- ✅ **`cyrano-cdi-vauban` séparé** : module optionnel, non chargé si CDI absent
- ✅ **TDD strict** sur tous les modules de production
- ✅ **TCK PASS 100 %** comme contrat dur
- ✅ **TCK hors reactor** (POM Model 4.0.0 standalone) — contrainte ShrinkWrap Maven Resolver 3.3
- ✅ **Virtual threads** pour tous les appels HTTP (`sendAsync` + `VirtualThreadPerTaskExecutor`)

## Décisions ouvertes

- [ ] Faut-il un module `cyrano-chappe` utilisant Chappe comme transport HTTP client
      (plutôt que JDK `java.net.http`) pour l'intégration vidocq-mps ? Ou JDK est suffisant ?
      → JDK `java.net.http` est la valeur par défaut ; `cyrano-chappe` serait un adapter optionnel.
- [ ] Support multipart/form-data (MIME multipart) ? Spec MP Rest Client 4.0 §3 le mentionne.
      → À confirmer à M2 en lisant la liste des tests TCK ciblés.
- [ ] Support `@ClientHeadersFactory` (header propagation depuis un contexte entrant) ?
      → Utile pour la propagation de JWT/trace ID. À évaluer en M3/M4.
- [ ] Support `SSE` (Server-Sent Events) côté client ?
      → Hors scope M0-M5 ; reporter post-TCK.
- [ ] `cyrano-bench` : comparatif vs RESTEasy Client / Jersey Client sur la même JVM ?
      → Ajouter en M5 ou post-M5 ; créer `BENCH.md` dès le premier chiffre mesuré.
