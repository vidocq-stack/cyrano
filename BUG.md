# BUG — Cyrano

Suivi des bugs reproductibles dans `cyrano` (issues internes, régressions, comportements
incorrects non encore corrigés). Convention workspace Vidocq : id court, date, symptôme,
repro minimal, hypothèse de cause, statut.

---

## CYR-001 — JPMS contourné via copie manuelle des JARs compile-scope

- **Date ouverture** : 2026-05-25
- **Statut** : ⚠️ OPEN — workaround actif

### Symptôme

Le `pom.xml` racine de cyrano utilise `maven-dependency-plugin` (phase `initialize`) pour
copier tous les JARs de scope compile dans `target/javamodules/`, puis passe
`--module-path ${project.build.directory}/javamodules` manuellement au compilateur.

Ce contournement indique que la résolution JPMS native de Maven ne fonctionne pas pour
certaines dépendances compile-scope de cyrano, notamment `microprofile-rest-client-api`,
`vauban-core`/`vauban-classloader-spi`, `champollion-jsonp`/`champollion-jsonb`.

### Repro minimal

```bash
grep -n "javamodules\|module-path" cyrano/pom.xml
# révèle les deux plugins configurés manuellement
```

Sans le workaround (suppression de la config `maven-dependency-plugin`), `javac` échoue avec :

```
error: module not found: org.eclipse.microprofile.rest.client
```

### Hypothèse de cause

Les JARs concernés ne disposent pas de `module-info.class` propre — ils n'exposent qu'un
`Automatic-Module-Name` dans leur `MANIFEST.MF`. La version 4.x du `maven-compiler-plugin`
ne les place pas automatiquement sur le `--module-path` pour les projets ayant un
`module-info.java` explicite. La copie dans `target/javamodules/` permet à javac de les
résoudre comme automatic modules en dérivant leur nom depuis le nom de fichier JAR.

### Piste de résolution

1. Vérifier si une version amont de `microprofile-rest-client-api` publie un
   `module-info.class`. Si oui, bumper la version et supprimer le workaround.
2. Contacter / PR upstream Eclipse MicroProfile pour ajouter un descripteur modulaire.
3. À défaut, wrapper via un module Cyrano interne (`cyrano-mp-rest-client-api`) qui fournit
   le `module-info.class` manquant — pattern déjà utilisé pour `ravel-mp-config-api`.
