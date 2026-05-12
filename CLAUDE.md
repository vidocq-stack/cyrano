# Cyrano - Claude Code Guidelines

> Cyrano de Bergerac (1619–1655) parlait au nom des autres, leur prêtant son éloquence
> pour séduire qui ils ne pouvaient atteindre seuls.
> C'est exactement ce que fait un client REST typé : il parle au nom du code applicatif,
> lui prêtant ses proxies et ses annotations pour appeler les services distants sans que
> l'appelant ait à connaître le protocole ou le transport sous-jacent.

## Prérequis

- **Java 25** + **Maven 4.0.0-rc-5** (`.sdkmanrc` fourni — utiliser `sdk env`)
- Le TCK MicroProfile Rest Client 4.0 est un artefact **public Maven Central** :
  `org.eclipse.microprofile.rest.client:microprofile-rest-client-tck:4.0`
  (contrairement aux TCK Jakarta, pas besoin de l'installer manuellement).
- **Note JPMS :** vérifier à M0 si `microprofile-rest-client-api` dispose d'un
  `Automatic-Module-Name` ou d'un `module-info.class`. Si non, créer un module
  `cyrano-mp-rest-client-api` de repackage (même patron que `ravel-mp-config-api`).

## Commandes essentielles

```bash
# Build du reactor (sans TCK)
./mvnw -ntp install -DskipTests

# Tests unitaires
./mvnw test

# TCK — smoke test seulement
./run-official-tck-mp-rest-client-4.0.sh

# TCK — suite complète
./run-official-tck-mp-rest-client-4.0.sh all

# TCK — test ciblé
./run-official-tck-mp-rest-client-4.0.sh -Dtest=NomDuTest
```

> `cyrano-tck` est **hors reactor** (POM Model 4.0.0 standalone) pour contourner
> ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0 — même contrainte que `cassini-tck`,
> `foy-tck`, `champollion-tck`, `ravel-tck` et `knock-tck`. Ne pas changer ce modèle.

## Architecture

Cyrano est une implémentation MicroProfile Rest Client 4.0, **zéro librairie tierce**
(pas de RESTEasy Client, CXF, Jersey Client, OkHttp), uniquement des specs Jakarta EE /
MicroProfile en dépendances, virtual threads, JPMS strict.

```
cyrano-api          ← Re-expose la spec org.eclipse.microprofile.rest.client
                     (RestClientBuilder, @RegisterRestClient, ClientHeaderParam, etc.)
cyrano-core         ← Implémentation : scanning d'interfaces, génération de proxy via
                     Class-File API (JEP 484), transport JDK HttpClient, param binding,
                     MessageBodyReader/Writer via Jakarta JSON-B (champollion)
cyrano-cdi-vauban   ← Intégration CDI Vauban : BCE @RegisterRestClient, @Inject @RestClient,
                     config de base URL via MicroProfile Config (Ravel)
cyrano-tck          ← Runner TCK officiel MicroProfile Rest Client 4.0 (HORS reactor)
```

**Flux d'un appel client :**
`@Inject @RestClient MyService client` → proxy `Cyrano$MyService` (généré par Class-File API)
→ `CyranoInvocationHandler` → construction `HttpRequest` (JDK `java.net.http`)
→ `CyranoHttpTransport` (virtual thread) → désérialisation réponse (Jakarta JSON-B / champollion)
→ valeur de retour typée.

**Génération de proxy — Class-File API (JEP 484) :**
Au lieu de `java.lang.reflect.Proxy` (réflexion dynamique), Cyrano génère à la première
utilisation une vraie classe nommée `Cyrano$<InterfaceName>` via l'API `ClassFile` du JDK 25.
Le bytecode est mis en cache par `CyranoProxyCache` (concurrent, lazy-init) et chargé dans
un `MethodHandles.Lookup.defineClass`. Aucune dépendance ASM/Byte Buddy.

**Annotations JAX-RS supportées (sur les interfaces client) :**
- HTTP methods : `@GET`, `@POST`, `@PUT`, `@DELETE`, `@PATCH`, `@HEAD`, `@OPTIONS`
- Path : `@Path`, `@PathParam`, `@QueryParam`, `@MatrixParam`
- Headers : `@HeaderParam`, `@CookieParam`, `@ClientHeaderParam`
- Body : `@Consumes`, `@Produces`, `@FormParam`, `@BeanParam`
- Context : `@Context` (limité)

**Types de retour supportés :**
- Types primitifs et leurs wrappers
- `String`, `jakarta.ws.rs.core.Response`
- POJO désérialisé via Jakarta JSON-B (champollion)
- `Optional<T>`, `List<T>`, `Set<T>`, `Map<K,V>`
- `CompletionStage<T>` (async, via virtual threads)

## Contraintes d'architecture à ne pas violer

1. **`cyrano-core` ne dépend que de `jakarta.ws.rs` + `jakarta.json.bind`** (API spec) —
   pas de CDI, pas d'implémentation JAX-RS serveur (Cassini). Transport = `java.net.http.HttpClient`
   (JDK pur). Champollion est l'implémentation JSON-B fournie à l'exécution.
2. **`cyrano-cdi-vauban` dépend de `cyrano-core` + `jakarta.cdi`** mais jamais l'inverse —
   l'intégration CDI est un module optionnel invisible depuis le cœur.
3. **Pas de `java.lang.reflect.Proxy`** — générer de vraies classes nommées via Class-File API
   (JEP 484). Avantage : compatible AOT (GraalVM `native-image`, Leyden CDS), stack traces
   lisibles, pas de `setAccessible(true)`.
4. **Transport via `java.net.http.HttpClient`** — zero-dep, virtual thread executor natif
   (`HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor())`).
5. **JPMS strict** : tous les modules ont un `module-info.java`, packages `internal.*`
   non exportés, SPI exposée uniquement via `provides ... with`.
6. **Pas de `synchronized`, pas de `ThreadLocal`** — virtual-thread-friendly. `ScopedValue`
   si propagation de contexte nécessaire (ex. traçage de requête).
7. **Pas de `setAccessible(true)` en production** — utiliser `MethodHandles.privateLookupIn`
   pour l'instanciation interne si nécessaire. Documenter toute ouverture JPMS.
8. **Champollion = seule lib JSON** — pas de Jackson, Gson, Jsonb standalone tierce.
   `cyrano-core` déclare `requires jakarta.json.bind` (spec) ; champollion est fourni runtime.
9. **TCK MicroProfile Rest Client 4.0 PASS à 100 %** est un contrat avant tout merge structurel.

## Conventions

- **Java modules explicites** : tous les modules ont un `module-info.java`.
- **Packages** :
  - `io.vidocq.cyrano.spi.*` = SPI public stable (TransportAdapter, ExceptionMapper, intercepteurs)
  - `io.vidocq.cyrano.internal.*` = code interne (peut casser entre versions)
- **Maven groupId** : `io.vidocq.cyrano`.
- **Records** pour les objets immuables (`RequestSpec`, `ResponseSpec`, `ParamBinding`) ;
  **sealed interfaces** pour les hiérarchies fermées (types de paramètre, résultats de dispatch).
- **Pattern matching** exhaustif sur switch — pas de chaîne `if/else if`.
- **JUnit 6** uniquement pour les tests (BOM `org.junit:junit-bom` 6.x).

## TDD — Test-Driven Development (obligatoire)

Cyrano est développé en **TDD strict**, dans cet ordre :

1. **Red** — écrire le test qui décrit le comportement attendu (citation section spec
   MicroProfile Rest Client 4.0 en commentaire JavaDoc). Le test doit échouer pour la
   bonne raison (compilation OK, assertion KO).
2. **Green** — écrire le minimum de code pour faire passer le test.
3. **Refactor** — nettoyer en gardant les tests verts. Lancer la suite complète du
   module avant tout commit.

Règles concrètes :

- **Un test par classe publique**, nommé `<Classe>Test`, dans le même package (`src/test/java`).
- **Pas de Mockito** — doubles écrits à la main ou serveurs de test `HttpServer` JDK inline.
- **Tests par fixture spec** : pour chaque section de la spec MicroProfile Rest Client 4.0
  référencée, un test nommé `<methode>_spec_section<X>_<Y>()`.
- **Serveur mock léger** pour les tests d'intégration de `cyrano-core` : `HttpServer` JDK
  (`com.sun.net.httpserver.HttpServer`) ou port Chappe minimal — aucune lib tierce.

## TCK — Technology Compatibility Kit

MicroProfile Rest Client TCK — exécuté dans un module hors reactor (`cyrano-tck`,
POM Model 4.0.0) pour contourner ShrinkWrap Maven Resolver 3.3 :

| TCK | Artifact | Cible |
|---|---|---|
| MicroProfile Rest Client 4.0 | `org.eclipse.microprofile.rest.client:microprofile-rest-client-tck:4.0` | 100 % PASS (contrat) |

Le script `run-official-tck-mp-rest-client-4.0.sh` :

- supporte `smoke` (par défaut), `all`, et `-Dtest=NomDuTest` ciblé ;
- installe le reactor en local (`mvn install -DskipTests`) avant invocation ;
- produit un rapport `target/tck-report.txt` avec le score PASS/FAIL/SKIP.

**Architecture du runner TCK :**

Le TCK MicroProfile Rest Client exige un backend HTTP qui joue le rôle de serveur cible.
Le `CyranoDeployableContainer` (custom Arquillian, ~300 LOC, test-scope only) :
- Démarre une instance Cassini+Chappe embedded sur un port aléatoire pour servir les
  ressources JAX-RS du TCK (côté serveur).
- Configure le `RestClientBuilder` de Cyrano pour pointer sur ce serveur (côté client).
- Réutilise `CassiniTestHarness` (cassini-tck) comme composant de test partagé.
- Aucune dépendance à Weld, Undertow, ou tout container tiers.

**Discipline de release :**

- **Aucun merge structurel** sur `cyrano-core`/`cyrano-cdi-vauban` sans TCK PASS.
- Les éventuels challenges (tests désactivés pour interprétation spec ou bug TCK) sont
  documentés dans `TCK.md` avec citation spec, hash du test, et plan de réactivation.

## Principes IA — collaboration sur ce dépôt

- **Plan mode par défaut** sur tout changement structurel (nouveau module, nouvelle SPI,
  modification du générateur de proxy ou du transport HTTP).
- **Class-File API d'abord** : pour tout ce qui ressemble à de la génération dynamique,
  préférer JEP 484 + `MethodHandles.Lookup.defineClass` à `java.lang.reflect.Proxy`.
  Utiliser l'agent `classfile-codegen` pour revue de tout nouveau générateur de bytecode.
- **Élégance équilibrée** : préférer un design simple qui passe le TCK à un design parfait
  qui ne le passe pas. Documenter les arbitrages dans des ADR (`docs/adr/`).
- **Pas de paresse sur les specs** : citer la section MicroProfile Rest Client 4.0 dans les
  commentaires de code quand l'implémentation y répond directement.
- **Zéro librairie tierce** : les specs Jakarta EE et MicroProfile sont les seules
  dépendances autorisées en scope `provided`/`compile`. Si une lib d'implémentation semble
  nécessaire, c'est qu'on s'est trompé de découpe.
- Utiliser les agents **`jpms-guardian`**, **`virtual-threads-reviewer`**,
  **`dependency-gatekeeper`**, **`classfile-codegen`** proactivement sur toute modification
  de `module-info.java`, code concurrent, `pom.xml`, ou générateur de bytecode.
- Si les règles de ce fichier doivent être mises à jour, penser à aligner `AGENTS.md` de la
  même façon, pour que Copilot Code puisse s'y référer facilement.
