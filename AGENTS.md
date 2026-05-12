# AGENTS.md

## Mission du dépôt

- Cyrano implémente **MicroProfile Rest Client 4.0** en Java 25, avec **zéro librairie tierce
  d'implémentation** : seulement la spec MP Rest Client + Jakarta APIs en dépendances
  (`README.md`, `pom.xml`, `CLAUDE.md`).
- Architecture JPMS stricte : `cyrano-api` ré-exporte la spec, `cyrano-core` reste standalone SE
  (dépend de `jakarta.ws.rs` / `jakarta.json.bind` pour les annotations et la sérialisation),
  `cyrano-cdi-vauban` est un adaptateur CDI optionnel, `cyrano-tck` reste hors reactor.
- **Génération de proxy via Class-File API (JEP 484)** : pas de `java.lang.reflect.Proxy`,
  pas d'ASM/Byte Buddy. Cyrano génère des classes nommées (`Cyrano$<Interface>`) via
  `ClassFile` + `MethodHandles.Lookup.defineClass` — compatible AOT, stack traces lisibles.
- **Transport via JDK `java.net.http.HttpClient`** avec `VirtualThreadPerTaskExecutor` —
  aucune dépendance réseau externe.
- Utiliser de préférence `ROADMAP.md` pour suivre l'avancement du projet plutôt que de mettre
  à jour ce fichier, qui est destiné à être un guide de contribution pour les agents.
- Si les règles de ce fichier doivent être mises à jour, penser à aligner `CLAUDE.md` de la
  même façon, pour que Claude Code puisse s'y référer facilement.

## État réel du code à connaître avant de modifier

- Consulter `ROADMAP.md` pour l'état détaillé de chaque milestone (M0..M5).
- Les milestones marqués ✅ sont terminés ; les autres sont en attente ou en cours.
- Le flux cible dans `cyrano-core` :
  `RestClientBuilder.newBuilder().baseUri(uri).build(MyService.class)` →
  `CyranoProxyCache.getOrGenerate(MyService.class)` (Class-File API, lazy, threadsafe) →
  instance proxy `Cyrano$MyService` →
  appels de méthode interceptés via `CyranoInvocationHandler` →
  `CyranoHttpTransport` (JDK HttpClient, virtual thread) →
  réponse désérialisée via Jakarta JSON-B (champollion).
- La SPI runtime exportée est `io.vidocq.cyrano.spi.*` :
  `TransportAdapter`, `ResponseExceptionMapper`, intercepteurs client.
  Les adaptateurs (`cyrano-cdi-vauban`) ne doivent jamais dépendre de `io.vidocq.cyrano.internal.*`.

## Frontières à ne pas casser

- Ne jamais remettre `cyrano-tck` dans le reactor : le parent `pom.xml` l'exclut
  volontairement à cause de ShrinkWrap Maven Resolver / Model 4.0.0 vs 4.1.0.
- `cyrano-core` dépend uniquement de `jakarta.ws.rs` (annotations JAX-RS, API spec) et de
  `jakarta.json.bind` (API JSON-B spec) ; CDI reste dans `cyrano-cdi-vauban`. Le transport
  est `java.net.http` (JDK). Champollion est l'implémentation runtime de JSON-B.
- **Pas de `java.lang.reflect.Proxy`** : tout proxy doit passer par Class-File API JEP 484.
  Utiliser l'agent `classfile-codegen` pour toute revue du générateur de bytecode.
- Garder `io.vidocq.cyrano.internal.*` non exporté ; toute extension passe par la SPI.
- Pas de `synchronized`, pas de `ThreadLocal` — virtual-thread-friendly.
  `CyranoProxyCache` utilise `ConcurrentHashMap.computeIfAbsent`.
- Pas de `setAccessible(true)` en production — utiliser `MethodHandles.privateLookupIn`
  si un accès interne est nécessaire. Documenter dans le `module-info` toute ouverture.
- **JUnit 6 minimum** (`org.junit:junit-bom` ≥ 6.0.3) pour tous les tests.
- JSON via Jakarta JSON-B (`jakarta.json.bind.Jsonb`) uniquement — champollion en est
  l'implémentation. Jamais Jackson, Gson, ni Yasson standalone.

## Workflows utiles

```bash
sdk env
./mvnw -ntp install -DskipTests
./mvnw test
./run-official-tck-mp-rest-client-4.0.sh
./run-official-tck-mp-rest-client-4.0.sh all
./run-official-tck-mp-rest-client-4.0.sh -Dtest=NomDuTest
```

- Le TCK passe toujours par le script racine, qui installe d'abord le reactor puis invoque
  `mvn -f cyrano-tck/pom.xml -Ptck-official test`.
- Le script TCK installe explicitement `cyrano-api,cyrano-core,cyrano-cdi-vauban` via
  `./mvnw -pl ... -am install -DskipTests` avant d'exécuter `cyrano-tck`.
- Le TCK exige un serveur backend (ressources JAX-RS du TCK) : le `CyranoDeployableContainer`
  démarre Cassini+Chappe embedded en test-scope. Voir `ROADMAP.md#M4` pour les détails.

## Conventions de contribution observées

- TDD strict : Red → Green → Refactor, avec citation de la section MicroProfile Rest Client
  visée dans les tests (`CLAUDE.md`, `ROADMAP.md`).
- Tests dans le même package, nommés `<Classe>Test` ; pas de Mockito — doubles manuels ou
  `HttpServer` JDK inline pour simuler le backend.
- La génération de bytecode est testée en vérifiant le comportement observable (appels HTTP,
  paramètres, retours), pas l'inspection du bytecode généré.
- La perf est une contrainte de conception : le chemin chaud (proxy déjà généré, réponse 200)
  ne doit pas allouer plus que nécessaire.

## Ce qu'un agent doit supposer pour les prochaines tâches

- `cyrano-core` est la brique fondatrice : `CyranoRestClientBuilder`, `CyranoProxyGenerator`
  (Class-File API), `CyranoHttpTransport` (JDK HttpClient), `CyranoInvocationHandler`.
  Aucun de ces composants ne doit importer de classes CDI, de classes Cassini internes,
  ou de librairies tierces.
- `cyrano-cdi-vauban` est un adaptateur optionnel : BCE `CyranoRestClientExtension` qui
  découvre les interfaces `@RegisterRestClient` et produit les beans CDI correspondants.
  La configuration de base URL passe par MicroProfile Config (Ravel) si disponible.
- Le TCK exige un `RestClientBuilder.newBuilder()` programmatique et une injection CDI
  `@Inject @RestClient`. Les deux chemins doivent aboutir au même proxy Class-File API.
- Avant toute modification structurelle de `cyrano-core` ou `cyrano-cdi-vauban`, raisonner
  avec le contrat final : **TCK MicroProfile Rest Client 4.0 à 100 % PASS**.
