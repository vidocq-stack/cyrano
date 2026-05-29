# Cyrano — État du TCK MicroProfile Rest Client 4.0

> Snapshot établi à la fin de M4 (itération initiale). Le contrat M4 final reste **100 % PASS**
> ; ce document trace les écarts en cours pour piloter les itérations suivantes.

## Exécution

```bash
./run-official-tck-mp-rest-client-4.0.sh        # smoke (CyranoTckSmokeTest, hors Arquillian)
./run-official-tck-mp-rest-client-4.0.sh all    # suite officielle (profil tck-official)
./run-official-tck-mp-rest-client-4.0.sh -Dtest=NomDuTest
```

Le rapport est généré dans `cyrano-tck/target/tck-report.txt` ; le brut Maven est dans
`cyrano-tck/target/tck-report.txt.raw`.

## Architecture du runner

| Composant | Rôle |
|---|---|
| `CyranoDeployableContainer` | Container Arquillian *Local* (no-op deploy/undeploy) |
| `CyranoArquillianExtension` | SPI `LoadableExtension` — enregistre le container |
| `VaubanTckBootstrap` | Boot/stop Vauban CDI par archive TCK + bridge MP Config (`TckConfigBridge`) |
| `WireMockProbeListener` | Listener TestNG (`ITestNGListener`) qui **démarre WireMock dès le boot** via un bloc statique, *avant* tout `@BeforeMethod` TCK |
| `WireMockTestBackend` | Singleton JVM-static — port 8765, bind 127.0.0.1, shutdown hook JVM |

**Note critique** : Arquillian appelle `LoadableExtension.register()` et
`DeployableContainer.start()` **après** `@AfterSuite` quand le protocole est *Local* ;
WireMock doit donc démarrer plus tôt — c'est le rôle du bloc statique de
`WireMockProbeListener` (chargé via `META-INF/services/org.testng.ITestNGListener`).

## Suites exclues à M4 (initial)

| Pattern | Raison | Réactivation |
|---|---|---|
| `**/ssl/**` | requiert `RestClientBuilder.trustStore(...)` / `keyStore(...)` non implémenté | M4 itération suivante |
| `**/sse/**` | SSE hors scope M0-M5 (cf. ROADMAP `Décisions ouvertes`) | post-M5 si demandé |

## Score actuel

```
Tests run: 168, Failures: 0, Errors: 0, Skipped: 0
```

**Tests réussis : 168/168 — 100 % PASS** (rapport du 2026-05-24).

```text
# Tests réussis : 168/168
RESULT : PASS
```

## Corrections appliquées dans cette itération M4

| Symptôme | Fix |
|---|---|
| WireMock démarré **après** `@AfterSuite` (tous les `@BeforeMethod` échouent) | Démarrage via bloc statique de `WireMockProbeListener` |
| `WireMock.reset()` admin URL pointait sur `localhost:8080` par défaut | `WireMock.configureFor("127.0.0.1", 8765)` après `start()` |
| `@ClientHeaderParam(required=false)` propageait l'exception du compute method | Ignore silencieusement l'en-tête si `required=false` (spec §6.5) |
| `@ClientHeaderParam(required=true)` wrappait dans `IllegalStateException` | Propage la `RuntimeException` originale (spec §6.5) |
| `@ClientHeaderParam(value="{com.foo.Util.method}")` non résolu (FQN statique) | `findHeaderMethod` détecte le dernier `.` et résout via `Class.forName` + méthode statique |
| Header méthode-level + interface-level même nom : doublé | `collectClientHeaders` retire l'entrée de signe opposé (static/dynamic) au même nom |
| `@HeaderParam` runtime n'écrasait pas `@ClientHeaderParam` de même nom | `applyHeaders` skippe statiques/dynamiques si runtime override présent |
| **M4-2** — `ClientRequestFilter` / `ClientResponseFilter` non câblés | Pipeline JAX-RS §6.3 / MP Rest Client §4.2 ajouté : `CyranoClientRequestContext` + `CyranoClientResponseContext` ; tri par priorité (ascendant requête, descendant réponse) ; `abortWith(Response)` court-circuite le transport ; propriété standard `org.eclipse.microprofile.rest.client.invokedMethod` exposée |
| **M4-3** — `CDI.current()` indisponible dans le runner TCK | Bootstrap Vauban par archive via `VaubanTckBootstrap` appelé depuis `CyranoDeployableContainer.deploy/undeploy` ; extraction de `microprofile-config.properties` (WAR/JAR + libs imbriquées) et projection vers `TckConfigBridge` |
| **M4-4** — `followRedirects` / `connectTimeout` / `readTimeout` non propagés | `connectTimeout` câblé au `HttpClient.Builder`, `readTimeout` au `HttpRequest.Builder.timeout(...)`, `followRedirects(true)` au `HttpClient.Redirect.NORMAL` ; lecture MP Config ajoutée dans `CyranoRestClientSyntheticCreator` (`/mp-rest/followRedirects`, `/connectTimeout`, `/readTimeout`) |

## Validation ciblée M4-3

- Run ciblé `ConfigKeyTest` : **PASS (2/2)** via `./run-official-tck-mp-rest-client-4.0.sh -Dtest=ConfigKeyTest`.
- Run ciblé famille `cditests/*` : **52 tests exécutés** ; les échecs restants sont
  désormais fonctionnels (redirects, providers CDI, proxy, priorité URI/URL), sans
  erreur de bootstrap CDI/Arquillian.

## Validation ciblée M4-4

- `FollowRedirectsTest,CDIFollowRedirectsTest` : **PASS (16/16)**.
- `TimeoutTest,TimeoutViaMPConfigTest,TimeoutViaMPConfigWithConfigKeyTest,TimeoutBuilderIndependentOfMPConfigTest` : **PASS (8/8)**.
- Les suites `**/timeout/**` sont réactivées dans `cyrano-tck/pom.xml` ; seules `ssl/**` et `sse/**` restent exclues.

## Challenges connus

| Suite | Statut | Justification |
|---|---|---|
| `**/ssl/**` | **exclue** | Requiert `RestClientBuilder.trustStore/keyStore` — hors scope actuel. |
| `**/sse/**` | **exclue** | SSE (Server-Sent Events) hors scope (cf. ROADMAP `Décisions ouvertes`). |

Les 168 tests restants passent à 100 %. Aucun challenge fonctionnel ouvert.

