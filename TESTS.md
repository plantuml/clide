# TESTS.md — suivi des campagnes de test de clide sur PlantUML

Journal des tests menés par Claude (sandbox cloud, Linux, JDK 21) en
utilisant `clide` comme client IA sur le code de PlantUML. Chaque campagne
part d'un environnement vierge : clone GitHub `--depth 1` de `plantuml/clide`
et de `plantuml/plantuml`, `apt-get install openjdk-21-jdk-headless ant`,
extraction de `jdt-language-server-latest.zip` dans `jdtls/`, build `ant`
(~2 s), puis pilotage du daemon en pipant les commandes sur stdin
(protocole « un token par ligne »).

La section « État des points » en bas de ce fichier est le suivi à jour ;
les campagnes détaillent le contexte de chaque constat.

## Campagne 1 — 2026-07-30

État du dépôt : avant `PrintMode`, avant les pages `man`, avant la
correction d'érasure de `find_implementation`.

### Mise en place

- Build clide sans accroc (ant, 2 s). jdtls extrait de l'archive commitée.
- PlantUML : aucun `.classpath`/`.project` nécessaire — jdtls les génère au
  premier build (« invisible project »), en ajoutant chaque jar de `.clide/`
  comme bibliothèque. Constat d'abord manqué à cause d'un paragraphe périmé
  du CLAUDE.md (« projet non reconnu, `./gradlew eclipse` + commit ») qui a
  poussé à écrire ces fichiers à la main avant le premier lancement ;
  paragraphe corrigé depuis, et trace ajoutée au démarrage du daemon
  (`(4/4) Building project ... [OK] (generated .project/.classpath ...)`).
- Build PlantUML : 3633 fichiers `.java`, **0 erreur**, 1300 warnings dans
  584 fichiers. Les stubs commités (`ant`/`openpdf`/`teavm`) + jars JUnit
  suffisent.
- Latence : ~0,25 s par session client une fois le daemon up.

### Ce qui marche (missions de navigation réelles)

- `find_symbol UGraphic` → 28 résultats = la carte complète de la
  hiérarchie de rendu en une commande. Les méthodes sont remontées
  (`find_symbol getStringBounder` → 15 déclarations `[method]`), le
  matching flou/camelCase est utile en exploration.
- `find_reference` répond à des questions coûteuses au grep : « qui
  enregistre `CommandSkinParam` ? » → `CommonCommands` +
  `UBrexCommonCommands` en une commande ; 30 vrais appels de
  `UGraphic.startGroup`, déclaration exclue.
- `find_declaration type` sur une variable saute directement à la classe de
  son type déclaré (`diagram` → `TitledDiagram`).
- `find_implementation type` sur `TextBlockMemoized` → 138 sous-classes
  (transitives incluses).
- `print_diagnostics` : savoir que le projet compile sans rien relire.
- Validation de surface avant tout appel LSP : fichier inexistant, ligne
  hors bornes, symbole absent de la ligne → `?SYNTAX ERROR` immédiat.
- Le format `chemin:ligne:nom`, recopiable d'un résultat vers la commande
  suivante, est le bon design pour un client IA.

### Problèmes trouvés

1. **`find_implementation method` ratait les overrides par érasure** :
   sur `UGraphic.draw(<SHAPE extends UShape>)`, 3 implémentations
   remontées seulement — tous les `draw(UShape)` (délégateurs,
   intercepteurs...) manquaient. Découvert par contre-vérification grep.
   → corrigé, voir campagne 2.
2. **`search_regex` avec `<chemin initial>` relatif** renvoie
   silencieusement « 0 match in 0 file » (résolu contre le cwd du daemon,
   pas contre la racine projet — incohérent avec la notation `Symbol`).
3. **Sorties incohérentes** : les `find_*` affichent des chemins relatifs
   au projet, `search_regex` des chemins absolus.
4. **Verbosité du protocole en usage batch** : les prompts `> READY` /
   `> Get '...'` se mélangeaient à la sortie utile.
   → corrigé, voir campagne 2 (`PrintMode.AI`).

### Manques identifiés (par ordre de valeur pour un client IA)

- Rebuild à la demande après modification (voir campagne 2, test dédié).
- Lancement d'un test ciblé.
- Call hierarchy (arbre d'appels, vs la liste plate de `find_reference`).
- Type hierarchy structurée (direct vs transitif ; et les super-types).
- `list_members` : option pour inclure les membres hérités.
- Recherche de champs par nom (limite jdtls connue, documentée dans
  CLAUDE.md — pour mémoire).

## Campagne 2 — 2026-07-31 (commit a601c60)

RAZ complet de l'environnement, re-clone, re-build, daemon neuf.

### Nouveautés validées

- **`PrintMode.AI` par défaut** : sortie sans aucun octet de protocole,
  directement consommable en pipe. Session complète (3 commandes) en
  0,25 s. Règle le problème n°4 de la campagne 1.
- **Correction de l'érasure** : `find_implementation method` sur
  `UGraphic.draw` renvoie désormais **25** implémentations (au lieu de 3),
  tous les overrides `draw(UShape)` inclus, zéro faux positif. La passe de
  rattrapage (parcours des sous-types, correspondance nom + arité,
  déclenchée seulement quand des génériques sont en jeu) ne coûte rien sur
  le cas normal. Règle le problème n°1.
- **Pages `man`** : format man(1), sections ERRORS/SEE ALSO, contenu qui
  explique les bords (matching flou, quoi enchaîner ensuite). Suffisant
  pour utiliser clide sans lire CLAUDE.md. Détail : le `@Help` de `man`
  affiche encore « please write help of man » dans `help`/`help_ai` alors
  que sa page `@Manual` est écrite.
- **Garde-fous transactions** : `terminate` refusé tant que
  `$test_claude` est ouverte (message listant les ids), avertissement à la
  déconnexion, `rollback_transaction` propre.

### Régressions vérifiées

- `search_regex` relatif : toujours « 0 match in 0 file » silencieux
  (problème n°2 — déjà en tête de TODO.md). Sorties toujours en absolu
  (n°3).

### Découverte : `textDocument/typeDefinition` vers le JDK ne répond jamais

Isolé en quatre tests reproductibles (projet PlantUML, daemon sain) :

| Test | Cible | Résultat |
|---|---|---|
| `find_declaration type` sur `system` (`Diagram`) | type projet | instantané, correct |
| `find_declaration type` sur `singleton` (`PSystemBuilder`) | type projet | instantané, correct |
| `find_declaration type` sur `source` (`String`) / `factories` (`List<...>`) | type JDK | **aucune réponse, timeout 30 s** — reproduit 4×, y compris 2× de suite dans la même session |
| `hover` sur `String` | JDK | instantané (`java.lang.String`, module java.base) |
| `find_declaration method` sur `startsWith` | méthode JDK | rapide, mais « no definition found » |

Signature : jdtls ne répond jamais à `typeDefinition` quand la cible est un
type du JRE (le `src.zip` du JDK est pourtant présent) — ce n'est pas un
échauffement de décompilation (le second essai consécutif échoue pareil).
Le daemon survit : la commande suivante de la même session répond
normalement. Pistes : timeout plus court avec un message orientant
(« cible probablement externe au projet »), pré-détection côté clide, ou
creuser la configuration de source lookup de jdtls. À noter que le
« no definition found » de `definition` vers le JDK est le pendant
silencieux du même trou : sauter *dans* une bibliothèque n'est pas couvert.

### Test du trou « rebuild » en conditions réelles

Scénario : transaction ouverte, puis erreur volontaire (`UParam` →
`UParamXXX` dans `UGraphic.java`) introduite avec les outils d'édition
propres de Claude (pas via clide — aucune commande d'édition n'existe).

- `print_diagnostics errors` → toujours « 0 error(s) » : les diagnostics
  sont ceux du build de démarrage, figés.
- `diff_transaction` → « has not modified any file yet » : la transaction
  ne voit que les modifications passées par clide (par design).

Conclusion : tant qu'il n'existe pas de commande `build`/rebuild (ou une
commande d'édition clide qui rebuild), la priorité n°1 du projet — savoir
si ce qu'on vient d'écrire compile — n'est pas exploitable dans le
workflow réel d'un agent qui édite avec ses propres outils. C'est le
manque le plus important à ce jour.

### Mission de navigation (carte blanche)

« Comment PlantUML choisit le type de diagramme » — résolu en 4 commandes :
`find_symbol PSystemBuilder` → `list_members` (singleton,
`List<PSystemFactory> factories`, `createPSystem`) → `find_reference
createPSystem` → un seul appelant réel (`BlockUml.java:194`) →
`search_regex factories\.add` → la liste des factories, une par type de
diagramme. L'enchaînement résultat → commande suivante sans retouche est
le point fort confirmé de l'outil.

## Campagne 3 — 2026-07-31 (commit 8f2325c « command rebuild »)

RAZ complet de l'environnement, re-clone, re-build, daemon neuf.

### `rebuild` : la boucle priorité n°1 est fermée

Scénario complet, éditions faites avec les outils propres de Claude (jamais
via clide), sur PlantUML entier :

1. `rebuild errors` à vide → « 0 file(s) changed since the last build,
   rebuilt in 11785 ms », 0 erreur — baseline.
2. Erreur volontaire (`String` → `StringXXX` ligne 98 de `BlockUml.java`)
   → `rebuild` : « 1 file(s) changed », **`[error] line 98: StringXXX
   cannot be resolved to a type`** — fichier, ligne et message exacts.
3. Correction + création d'un nouveau `TestClaude.java` cassé → « 2
   file(s) changed », l'erreur du nouveau fichier remontée (`Type
   mismatch: cannot convert from String to int`).
4. Suppression de `TestClaude.java` → « 1 file(s) changed », retour à
   0 erreur, 584 fichiers.
5. **Le modèle sémantique est rafraîchi, pas seulement les diagnostics** :
   méthode `claudeProbe()` ajoutée hors clide, `rebuild`, puis
   `find_symbol claudeProbe` → trouvée, à la bonne ligne.

Coût mesuré : 9 à 12 s par `rebuild` sur PlantUML (3600+ fichiers), que des
fichiers aient changé ou non — un `rebuild` à 0 changement paie le build
complet quand même (piste mineure : court-circuiter vers les diagnostics du
dernier build dans ce cas, ou le laisser tel quel comme « build forcé »).

Conclusion : la boucle réelle d'un agent — éditer avec ses propres outils →
`rebuild` → erreurs exactes → naviguer sur le modèle à jour — fonctionne de
bout en bout. Le point n°7 (priorité n°1 du projet) est réglé.

### `search_regex` : chemins relatifs corrigés

- `<chemin initial>` relatif (`src/main/java`) → fonctionne (7 matches sur
  le motif témoin, identiques à l'absolu). Point n°2 réglé.
- `.` pour chercher dans tout le projet → fonctionne (9 matches, incluant
  les fichiers hors `src/main`). Conforme au TODO.
- Les sorties sont désormais en chemins **relatifs au projet**, cohérentes
  avec les `find_*`. Point n°3 réglé.
- Un chemin absolu dans le projet fonctionne toujours ; un chemin hors
  projet (`/tmp`) renvoie 0 match silencieux (non testé plus loin).

### Régressions toujours ouvertes

- **Point n°5 inchangé** : `find_declaration type` vers un type JDK
  (`String`) → `No response for textDocument/typeDefinition after 30s`,
  reproduit sur ce daemon neuf.
- **Point n°6 inchangé** : `help_ai` affiche toujours « man <Keyword> -
  please write help of man ».

## Campagne 4 — 2026-08-01 (branche `tmp-truc`, `JunitVendorJars` sur PlantUML)

Objectif initial : tester `run_test`/`run_tests` sur PlantUML (branche
`clide`). `ant test` en local (96/96, avant le chantier `EclipseProjectFiles`
d'Arnaud, poussé en cours de route — voir plus bas) confirme d'abord que
clide lui-même est sain.

### Le trou : compilation des tests cassée sans JUnit dans `.clide/`

`run_test`/`run_tests` sur PlantUML (branche `clide`, dépouillée de tout jar
JUnit dans `.clide/` — seuls des stubs et `opentest4j` y sont) rapportait à
tort « aucun test trouvé ». Cause réelle, trouvée via `print_diagnostics
errors` : **6058 erreurs de compilation** — `clide.jar` embarque JUnit pour
*exécuter* les tests, mais jamais pour que jdtls les *compile* (voir
CLAUDE.md, section « JUnit pour un projet cible qui n'en a aucun »). Validé
d'abord à la main (jars copiés dans `.clide/`, 6058 → 6 erreurs restantes,
sans rapport avec JUnit), puis automatisé (`JunitVendorJars`).

### Reconciliation avec le chantier `EclipseProjectFiles` (en cours en parallèle)

Le correctif a d'abord été développé et testé (96/96, bout en bout sur
PlantUML) sur une base locale qui s'est révélée en retard de 5 commits sur
`origin/tmp-truc` : Arnaud avait en parallèle remplacé
`ensureDotFilesPresent()` par `EclipseProjectFiles` (stage/unstage complet
de `.project`/`.classpath`, plus relocalisation de `.clide.lock`/
`.clide-daemon.log` sous `.clide/tmp/`) et ajouté le support d'un
`clide.jar` autoportant (`resource/jdt-language-server-latest.zip`
embarqué). Après son push, réintégration propre sur `origin/tmp-truc`
(`fee97fd`) : `JunitVendorJars.ensurePresent()` appelé juste avant
`EclipseProjectFiles.stage(...)` dans `JdtlsSession.start()`,
`JunitVendorJars.TARGET_DIR` dérivé de `EclipseProjectFiles.STAGING_DIR`
plutôt que recodé, jars vendus placés sous `resource/vendor-junit/` dans
`clide.jar` (même convention de premier niveau `resource/` que le zip
jdtls). `ant test` : 104/104 (96 + 10 `JunitVendorJarsTest` + 8
`EclipseProjectFilesTest` — inchangée par ce correctif).

### Testé de bout en bout (clone PlantUML neuf, `clide.jar` reconstruit)

- `.clide/tmp/jar-junit/` peuplé au premier démarrage du daemon (3 jars).
- `.classpath` : jars du `.clide/` du projet d'abord, puis ceux de
  `jar-junit/` — précédence « le projet cible gagne » vérifiée.
- `.clide/tmp/.gitignore` (`*`) posé automatiquement — couvre aussi
  `.clide.lock`/`.clide-daemon.log`/les fichiers stagés d'`EclipseProjectFiles`,
  pas seulement `jar-junit/`.
- `print_diagnostics errors` : 6058 → **6** (identique à la validation
  manuelle — les 6 restantes viennent de `RandomBeansExtension`, une
  dépendance de test distincte, hors périmètre).
- `run_test` : `JsonObjectTest` 8/8, `UrlBuilderTest` 20/20, `MathTest`
  12/12 — tous passés.
- `git status --porcelain` vide après `terminate`, y compris pendant la
  session (rien à la racine, tout sous `.clide/tmp/` gitignoré).

Effet de bord découvert au passage : la syntaxe des commandes est bien un
token par ligne (`print_diagnostics`\n`errors`, pas `print_diagnostics
errors` sur une ligne) et `run_test` attend la notation
`<chemin>:<ligne>:<nom>`, pas un simple nom de classe — cohérent avec
CLAUDE.md (section « Notation... ») mais qui vaut la peine d'être
redit ici : une tentative naïve avec juste le nom de classe échoue en
`SYNTAX ERROR`, pas en « test introuvable ».

## État des points

| # | Point | Origine | Statut |
|---|---|---|---|
| 1 | `find_implementation` et overrides par érasure | C1 | **corrigé** (C2, passe nom+arité) |
| 2 | `search_regex` chemin relatif → 0 silencieux | C1 | **corrigé** (C3, relatif au projet + `.`) |
| 3 | Sorties `search_regex` en absolu (vs relatif ailleurs) | C1 | **corrigé** (C3, sorties relatives) |
| 4 | Bruit de protocole en usage batch | C1 | **corrigé** (C2, `PrintMode.AI`) |
| 5 | `typeDefinition` vers un type JDK : aucune réponse, timeout 30 s | C2 | ouvert |
| 6 | `@Help` de `man` : placeholder « please write help of man » | C2 | ouvert (mineur) |
| 7 | Commande `build`/rebuild + diagnostics après édition | C1+C2 | **corrigé** (C3, `rebuild` — 9-12 s sur PlantUML, modèle sémantique inclus) |
| 8 | Lancement d'un test ciblé | C1 | **corrigé** (C4, `run_test` sur `JsonObjectTest`/`UrlBuilderTest`/`MathTest`, une fois le trou de compilation JUnit comblé — voir point 14) |
| 9 | Call hierarchy (jdtls le supporte) | C1 | ouvert |
| 10 | Type hierarchy structurée / super-types | C1 | ouvert |
| 11 | `list_members` avec membres hérités (option) | C1 | ouvert (souhait) |
| 12 | Recherche de champs par nom | C1 | limite jdtls, non actionnable |
| 13 | `rebuild` à 0 changement paie le build complet (~11 s) | C3 | ouvert (mineur, peut-être voulu) |
| 14 | Compilation des tests cassée sans JUnit dans `.clide/` du projet cible (6058 erreurs sur PlantUML) | C4 | **corrigé** (C4, `JunitVendorJars` — extraction depuis `clide.jar` vers `.clide/tmp/jar-junit/`, aucun commit requis côté projet cible) |
| 15 | `run_tests` (suite complète, 259 classes PlantUML) : jamais terminé dans les 10 min du sandbox, probablement `graphviz`/`dot` manquant | C4 | ouvert |
