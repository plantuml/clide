# JDTLS.md — ce que jdtls peut apporter à clide

Ce document liste ce que jdtls (Eclipse JDT Language Server) sait faire, et ce
qui serait réellement utile pour clide, en reprenant les priorités fixées au
départ du projet (voir `CLAUDE.md`) : 1) compiler et récupérer les erreurs,
2) lancer un test ciblé, 3) requêtes sémantiques.

## 0. Premier jalon concret : vérifier que jdtls compile clide lui-même

**Statut : validé de bout en bout, y compris avec les fichiers `.project`/
`.classpath` réellement commités par Arnaud (générés via `./gradlew
eclipse`, poussés sur GitHub).**

Protocole suivi (via un harnais Python jetable, `initialize`/`initialized` en
JSON-RPC framing `Content-Length`) :

1. Démarrer jdtls (déjà fait — voir `JdtlsLauncher`).
2. Handshake `initialize` (avec `rootUri`/`workspaceFolders` pointés sur le
   dossier clide) → réponse → `initialized`.
3. `textDocument/didOpen` sur `Main.java` (ou `java/buildWorkspace` pour un
   build complet).
4. Lire les diagnostics publiés (`textDocument/publishDiagnostics`).
5. Test négatif : retirer un point-virgule dans `Main.java`, relancer — le
   diagnostic remonte exactement à la bonne ligne, avec le bon message
   (`"Syntax error, insert \";\" to complete BlockStatements"`, sévérité
   Error). Remettre le point-virgule → diagnostics vides. **Confirmé.**

### Découverte : jdtls ne compile clide correctement qu'à une condition

En l'état (juste `git clone` + extraction de l'archive jdtls), le premier
essai n'a donné **aucun diagnostic** — pas parce que tout compilait, mais
parce que jdtls n'avait reconnu aucun projet du tout :

- `java.project.getAll` renvoyait `[]`.
- Ouvrir `Main.java` donnait un diagnostic unique et révélateur : *"Main.java
  is a non-project file, only syntax errors are reported"* — mode dégradé,
  aucune vraie compilation sémantique.

En cause (visible dans `.metadata/.log` du workspace jdtls) : jdtls essaie
d'abord d'importer clide comme **projet Gradle** (ordre de priorité observé :
`GradleProjectImporter`, `MavenProjectImporter`, `EclipseProjectImporter`,
`InvisibleProjectImporter`), via Buildship/Gradle Tooling API. Cet import a
besoin du réseau (récupérer les infos de version sur
`services.gradle.org/versions/all`, puis potentiellement télécharger la
distribution Gradle du wrapper) — bloqué depuis la sandbox. L'échec est
silencieux (un seul WARNING dans le log, rien de fatal) et jdtls ne bascule
**jamais** automatiquement sur un import Eclipse (`.project`/`.classpath`)
tant que `build.gradle.kts` est présent à la racine.

**Deux façons de contourner, testées toutes les deux :**

- *Radicale* : renommer/déplacer temporairement `build.gradle.kts` et
  `settings.gradle.kts` hors du dossier → jdtls retombe sur
  `EclipseProjectImporter` via `.project`/`.classpath` (générés via
  `./gradlew eclipse`) → projet reconnu, compilation réelle. Fonctionne, mais
  pas pratique (il faut déplacer les fichiers à chaque fois).
- **Propre et recommandée** : laisser `build.gradle.kts` en place, mais
  désactiver les importeurs Gradle/Maven directement dans la requête
  `initialize`, via `initializationOptions.settings.java.import.gradle.enabled
  = false` (et `maven.enabled = false`). jdtls saute alors directement sur
  `EclipseProjectImporter` — aucun fichier à déplacer, aucun appel réseau
  tenté. **Testé et confirmé** : `java.project.getAll` reconnaît le projet,
  `Main.java` compile avec 0 diagnostic.

**Pré-requis pour que ça marche** : un `.project`/`.classpath` Eclipse valide
à la racine de clide. **Fait** : ces fichiers sont commités dans git (retirés
du `.gitignore`) et testés directement depuis un clone GitHub frais dans la
sandbox — `java.project.getAll` reconnaît le projet, le classpath résolu
correspond à `bin/main` (cohérent avec le `.classpath` généré), et
`Main.java` compile avec 0 diagnostic. Test négatif (point-virgule retiré)
refait avec ces mêmes fichiers : diagnostic exact, disparaît une fois
corrigé.

**Point d'attention découvert en testant** : jdtls **réécrit lui-même
`.project`** pendant l'import (il y ajoute un filtre d'exclusion
`node_modules|\.git|__CREATED_BY_JAVA_LANGUAGE_SERVER__`). Rien de grave,
mais après un run de jdtls sur la machine d'Arnaud, `git status` peut
montrer `.project` modifié — à ne pas confondre avec une vraie régression.

C'est le test qui valide que toute la chaîne (process jdtls + protocole LSP +
compréhension du projet) fonctionne, avant de s'attaquer à PlantUML — où le
même problème d'import se posera, en plus sérieux vu le nombre de
dépendances réelles à résoudre.

## 0bis. Passage à l'échelle : `open_project` et PlantUML

`clide` gère maintenant plusieurs projets (commande `open_project <chemin>`,
une session/un jdtls par projet). Testé sur un clone frais de PlantUML
(`git clone --depth 1`, 3600 fichiers `.java`) :

- **Découverte de passage à l'échelle** : la première implémentation ouvrait
  chaque fichier individuellement (`textDocument/didOpen`), comme au jalon 0.
  Sur clide (5 fichiers) c'était instantané. Sur PlantUML, jdtls « reconcilie »
  chaque fichier ouvert en séquence à environ 140 ms/fichier (visible dans
  `.metadata/.log`, entrées `Reconciled N. Took M ms`) — soit plusieurs
  minutes pour tout le projet. Pas un blocage, juste inutilisable en
  pratique.
- **Solution retenue** : `java/buildWorkspace` seul, sans aucun `didOpen`,
  publie les diagnostics de tout le projet directement. Testé sur clide avec
  une erreur volontaire : réponse en 0,7 s, diagnostic correct. `JdtlsSession`
  utilise désormais uniquement cette approche.
- **Sur PlantUML tel quel** (sans `.classpath`/`.project`) : `open_project`
  reste rapide (pas de blocage, même à cette taille), mais `java.project.getAll`
  renvoie `[]` et `java/buildWorkspace` ne rapporte aucun diagnostic — exactement
  le même problème qu'au jalon 0 sur clide avant d'avoir un `.classpath`.
  **PlantUML a besoin du même traitement que clide** : `./gradlew eclipse`
  chez Arnaud, puis commit de `.project`/`.classpath`/`.settings`, pour que
  `open_project c:\github\plantuml` devienne vraiment utile.

## 0ter. Dépendances externes : le cache `.clide/*.jar` par projet

Même avec `.project`/`.classpath` généré (section 0bis), les dépendances
externes d'un projet restent non résolues : pas de conteneur Gradle possible
sans réseau (voir section 0). Sur un clone frais de PlantUML, ça se traduit
concrètement par :

| Portée | Fichiers en erreur | Cause dominante |
|---|---|---|
| `src/main` | 20 fichiers | `org.apache` (tâches Ant), `org.openpdf`, `org.teavm` |
| `src/test` | 312 fichiers | `org.junit` (1894 occurrences), `org.junitpioneer`, `org.assertj`, `org.mockito`, `org.xmlunit` |

**Mécanisme retenu** : un dossier `<projet>/.clide/` (un par projet ouvert,
pas un cache partagé), rempli à la main avec les `.jar` utiles. `clide` le
lit dans `JdtlsSession.detectJarLibs()` et ajoute une entrée
`classpathentry kind="lib"` par jar trouvé (chemin absolu, `/` normalisé) —
mais **uniquement quand `clide` génère lui-même `.classpath` depuis zéro**
(cas où le fichier est absent). Un `.classpath` déjà présent (généré à la
main via `./gradlew eclipse`, comme pour PlantUML aujourd'hui) n'est jamais
modifié — `.clide/` n'aurait alors aucun effet tant que ce fichier existe.

**Validé empiriquement** : 5 jars JUnit5 (`junit-jupiter-api`,
`-engine`, `-params`, `junit-platform-commons`, `opentest4j`) déposés dans
`plantuml/.clide/`, sur un clone où `.classpath` n'existait pas encore →
les erreurs passent de **6434 à 1634** (890 → 684 fichiers en erreur),
`org.junit` disparaît totalement des imports non résolus. Confirmé aussi que
l'absence de `.clide/` (cas de clide lui-même) ne casse rien.

## 1. Retour de compilation (priorité n°1)

- **`textDocument/publishDiagnostics`** (notification serveur → client) :
  envoyée automatiquement après `didOpen`/`didChange`, ou après un build
  explicite. Contient sévérité, message, ligne/colonne — exactement ce dont
  clide a besoin pour remplacer le rôle de compilateur que joue Claude
  aujourd'hui.
- **`java/buildWorkspace`** (extension jdtls, hors standard LSP) : déclenche
  un build complet du/des projets connus de jdtls et fait remonter les
  diagnostics. C'est l'équivalent LSP d'un `gradle build` ou `ant compile`,
  mais piloté par requête plutôt que par sous-processus externe. **C'est
  l'approche retenue dans `JdtlsSession`** (voir section 0bis) — bien plus
  rapide que d'ouvrir chaque fichier via `didOpen` sur un gros projet.

## 2. Navigation sémantique (priorité n°3)

- **`textDocument/definition`** / **`typeDefinition`** — où est réellement
  définie une méthode, une classe, une variable (pas un grep qui tombe sur un
  usage). **Fait, testé de bout en bout** : commandes
  `goto_definition`/`goto_type_definition` (voir `CLAUDE.md`). Position donnée
  en ligne (1-based) + texte du symbole cherché comme mot entier sur cette
  ligne, plutôt qu'une colonne brute — clide déduit la colonne lui-même. Testé
  sur clide lui-même (clone GitHub frais + jdtls extrait + `ant run`) :
  `goto_definition` sur `command`/`context` dans `Main.java` renvoie bien leur
  déclaration locale, `goto_type_definition` sur les mêmes symboles saute
  directement à `Command`/`ClideContext`. **Pas de `didOpen` préalable
  nécessaire** — confirmé, la requête aboutit directement sur le modèle du
  dernier `java/buildWorkspace` (reste à revalider à l'échelle de PlantUML,
  mais rien n'indique que ça devrait se comporter différemment).
- **`textDocument/implementation`** — quelles classes implémentent une
  interface donnée. **Fait, testé de bout en bout** : commande
  `goto_implementation` (voir `CLAUDE.md`), troisième sous-classe de
  `GotoPositionCommand` sans aucune logique nouvelle (même position par
  ligne+mot entier que `goto_definition`/`goto_type_definition`). Testé sur
  clide lui-même : `goto_implementation` sur la méthode abstraite
  `executeCommand` de `Command.java` renvoie exactement les 6 implémentations
  concrètes existantes, sans bruit.
- **`textDocument/references`** — qui utilise ce symbole, dans tout le
  projet.
- **`textDocument/prepareCallHierarchy`** +
  **`callHierarchy/incomingCalls`** / **`outgoingCalls`** — qui appelle cette
  méthode / qu'est-ce qu'elle appelle. C'est le besoin exprimé dès le début
  ("qui appelle cette méthode").
- **`textDocument/prepareTypeHierarchy`** +
  **`typeHierarchy/supertypes`** / **`subtypes`** — hiérarchie de classes et
  d'interfaces. Particulièrement utile sur une base aussi polymorphique que
  PlantUML.
- **`textDocument/documentSymbol`** — plan d'un fichier (classes, méthodes,
  champs), utile pour se repérer sans tout relire. **Fait** : commande
  `list_members` (voir `CLAUDE.md`), `JdtlsSession.listMembers` — restreinte
  aux membres directs d'un type précis plutôt qu'au plan complet du fichier,
  pour répondre au besoin exprimé ("lister les méthodes d'un type" sans
  grepper les appels à la main). Nécessite `hierarchicalDocumentSymbolSupport`
  déclaré côté client dans `initialize` (fait), sinon jdtls renvoie un
  `SymbolInformation[]` plat sans `children`. **Testé de bout en bout**
  (clone GitHub frais de `plantuml/clide`, jdtls extrait, self-test) : sur
  `Command` (classe abstraite sans champ), renvoie exactement ses 8 méthodes
  déclarées, dans l'ordre du fichier.
- **`workspace/symbol`** — recherche d'un symbole par nom dans tout le
  projet, sans connaître le fichier. **Fait** : commande `find_symbol` (voir
  `CLAUDE.md`), `JdtlsSession.findSymbol`. Aucun filtrage côté clide sur les
  résultats — le matching (flou/camelCase en pratique) reste entièrement celui
  de jdtls. **Testé de bout en bout** (même self-test) : `find_symbol
  JdtlsSession` renvoie `[class] .../JdtlsSession.java:34: ...`.
- **`textDocument/hover`** — signature/Javadoc d'un symbole, utile pour
  comprendre une API sans ouvrir le fichier source. **Fait** : commande
  `hover` (voir `CLAUDE.md`), `JdtlsSession.hover`. Gère les trois formes
  possibles de `Hover.contents` (`String`, `MarkupContent`/`MarkedString` en
  `{"value": ...}`, ou une liste mélangeant les deux) — jdtls choisit la
  forme, clide ne l'impose pas. **Testé de bout en bout** (même self-test) :
  jdtls renvoie ici un `MarkupContent` (Markdown), avec le Javadoc de la
  classe et un lien `Source: [...]` généré par jdtls, rendu correctement.
  Les deux autres formes (`String` seule, liste mélangée) restent seulement
  vérifiées par réflexion (voir plus haut) — pas encore vues en vrai.

## 3. Modifications outillées (utile, mais secondaire)

- **`textDocument/codeAction`** — quick fixes proposés par Eclipse (import
  manquant, etc.), organize imports.
- **`textDocument/rename`** — renommage sûr d'un symbole à travers tout le
  projet (plus fiable qu'un rename par recherche/remplacement texte).
- **`textDocument/formatting`** / **`rangeFormatting`** — reformatage selon
  les conventions du projet.

## 4. Extensions spécifiques à jdtls (hors standard LSP)

- **`java/classFileContents`** — récupère le contenu d'un `.class`. Utile
  pour lire une dépendance sans source attachée (par exemple une lib tierce
  de PlantUML) sans avoir à la décompiler soi-même.
- **`java/projectConfigurationUpdate`** — recharge la configuration après une
  modification de `build.gradle`/`pom.xml`.
- **`language/status`** — notifications de progression ; utile pour savoir
  quand jdtls a fini d'indexer le projet avant d'envoyer la première requête
  utile (l'indexation initiale peut prendre plusieurs minutes sur un gros
  projet comme PlantUML).
- **`language/actionableNotification`** — messages et actions proposés par le
  serveur.

## 5. Ce qui ne sert probablement à rien pour clide

- **`textDocument/completion`**, **`signatureHelp`** — pensés pour la frappe
  caractère par caractère dans un éditeur, pas utile pour Claude qui écrit du
  code déjà entier en un tour.
- **Exécution de tests unitaires** — jdtls seul ne sait pas lancer un test.
  Ça passe normalement par une extension séparée côté éditeurs (par exemple
  ce que fait l'extension VS Code "Test Runner for Java"), qui n'est pas
  incluse dans le build brut de jdtls téléchargé ici. À creuser séparément si
  besoin — priorité n°2 de clide reste donc probablement hors du périmètre
  strict de jdtls, plus proche d'un lancement direct via Gradle/Ant/JUnit
  console launcher.

## Pré-requis technique commun

- **Framing JSON-RPC** : chaque message est précédé d'un en-tête
  `Content-Length: N\r\n\r\n` (N = taille en octets du JSON qui suit).
- **Handshake obligatoire** : `initialize` → attendre la réponse →
  `initialized`, avant d'envoyer quoi que ce soit d'autre.
- **Description du projet** : jdtls a besoin de comprendre le classpath.
  Résolu pour clide (voir section 0) : `.classpath` Eclipse commité, plus
  `initializationOptions.settings.java.import.gradle.enabled = false` (et
  `maven.enabled = false`) pour éviter que jdtls ne tente d'abord
  l'import Gradle (prioritaire par défaut sur l'import Eclipse) et échoue
  silencieusement faute de réseau.
