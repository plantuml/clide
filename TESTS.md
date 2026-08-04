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
   pas contre la racine projet — incohérent avec la notation `Position`).
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

## Campagne 5 — 2026-08-04 (tests libres, HEAD)

RAZ complet : clone `--depth 1` de `plantuml/clide` et de `plantuml/plantuml`,
`apt-get install ant`, `ant dist` (8 s, `clide.jar` = 56 Mo, jdtls embarqué),
daemon neuf sur PlantUML (6363 fichiers, 584 fichiers Java compilés).
Particularité d'environnement importante pour le point 17 : `JAVA_TOOL_OPTIONS`
est positionné dans ce sandbox.

Campagne sans consigne : vérifier librement que clide marche. La méthode a été
de ne pas se contenter de constater que les commandes *répondent*, mais de
contre-vérifier leurs réponses contre `grep` et contre le code, dans les deux
sens (ce que clide ajoute, ce qu'il retire).

### Mise en place

| Étape | Mesure |
|---|---|
| `ant dist` | 8 s, aucun accès réseau |
| Démarrage à froid du daemon (1er CWD) | 45 s, extraction jdtls incluse |
| Reconnexion à un daemon vivant | **0,13 – 0,65 s** — conforme au `~0,25 s` annoncé |
| Build initial | 0 erreur, 1300 warnings, 584 fichiers |

Le protocole « un token par ligne » se comporte comme documenté : la commande
écrite sur une seule ligne renvoie bien `?SYNTAX ERROR` nu, la même éclatée en
lignes fonctionne. Validation de surface toujours nette (`Line 99999 out of
range (file has 79 line(s))`, `'notThere' not found on line 56`, `Not a file`).

### Requêtes sémantiques : exactitude contre-vérifiée

C'est le point le plus important de cette campagne. Les réponses de clide ont
été diffées avec celles de `grep` :

**`find_reference` sur `UGraphic.flushUg()`** → 10 emplacements.
`grep -rn "\.flushUg()"` en trouve 11. Le 11ᵉ est
`gtile/GtileGroup.java:207: //  interceptor2.flushUg();` — **ligne commentée**.
clide a raison, `grep` a un faux positif.

**`find_implementation method` sur `UGraphic.draw`** → 25 emplacements
(stable depuis la correction d'érasure de C2). Le motif `grep` équivalent
remonte 28 fichiers ; les 3 écarts sont tous des faux positifs de `grep` :

| Remonté par grep, exclu par clide | Pourquoi clide a raison |
|---|---|
| `decoration/symbol/USymbolFrame.java:179` | méthode **commentée** |
| `emoji/UGraphicWithScale.java:94` | classe qui **n'implémente pas** `UGraphic` — homonymie pure |
| `klimt/drawing/UGraphic.java:70` | c'est la **déclaration** de l'interface |

Et inversement clide remonte ce que le motif `grep` naïf raterait : la
générique `AbstractUGraphic.draw(SHAPE)` et deux **classes anonymes de test**
(`SvgNanoParserTest:84`, `SvgSaxParserFontWeightTest:195`).

**`find_reference` sur un champ** (`SvgGraphics:97:DEFAULT_FONT_FAMILY`) → 2
usages, corrects. Le point 12 ne concerne bien que `find_symbol`, pas les
commandes positionnelles — précision utile, la limite est plus étroite que ce
que CLAUDE.md laisse craindre.

`hover`, `list_members` (13 membres directs de `UGraphic`), `find_declaration
type` sur type projet, `find_symbol`, `search_regex` : tous conformes.

### `rebuild` : le scénario « refactor incomplet »

Test décisif, plus large que celui de C3 : changement de la signature d'une
méthode d'interface très implémentée, `UGraphic.flushUg()` → `flushUg(int
pass)`, puis `rebuild errors` :

```
jdtls: 47 error(s), 1299 warning(s) in 619 file(s)
  [error] line 59: The type CollisionDetector must implement the inherited abstract method UGraphic.flushUg(int)
  [error] line 259: The method flushUg(int) in the type UGraphic is not applicable for the arguments ()
  ...
  [error] line 180: The type SvgSaxParserFontWeightTest.RecordingUGraphic must implement ...
```

47 erreurs : implémentations manquantes **et** sites d'appel, classes internes
et anonymes de test comprises. C'est exactement la classe de bug qu'un agent ne
peut pas attraper en relisant son propre diff.

Sur un cas simple (2 erreurs injectées dans `UGraphicNull`), fichier, ligne et
message exacts, et rafraîchissement du modèle confirmé une fois de plus
(`find_symbol deliberateTypo` : rien avant `rebuild`, trouvé après).
`print_diagnostics` rejoue le résultat en **0,13 s** sans recompiler.

Coût mesuré du `rebuild` : **14 à 21 s** (3 mesures : 20,8 / 17,4 / 14,2 s),
contre les « 9 à 12 s » annoncés dans CLAUDE.md — chiffre à réactualiser.

### `run_tests` : le point 15 est levé

```
run_tests: 3063 test(s), 2976 passed, 79 failed, 8 skipped in 28018 ms
```

**28 secondes pour 3063 tests**, sur toute la suite PlantUML. L'avertissement
de C4 (« jamais terminé dans les 10 min du sandbox ») et celui de CLAUDE.md
(« peut ne jamais finir ») sont obsolètes.

Décomposition des 79 « échecs » : 70 `HeadlessException` (pas de X11 dans le
conteneur — environnemental), 1 `IOException`, et **5 `TestAbortedException`**
qui ne sont pas des échecs du tout — voir point 18.

À noter : `run_tests` modifie 3 fichiers de `src/test/resources/vega/`. C'est
PlantUML qui les réécrit, pas clide, mais c'est à savoir avant de lancer la
suite sur un arbre de travail non commité.

### Nouveaux problèmes trouvés

**16. `run_test` ne sait pas lancer une méthode qui prend des paramètres.**
Échoue sur **tout `@ParameterizedTest`**, et plus généralement sur toute
méthode à paramètres. Sur PlantUML c'est massif : `StringUtilsTest`,
`UrlBuilderTest`, `MathTest`, `StringDecipherTest`… n'ont *que* des tests
paramétrés. C4 n'a pas vu le trou parce qu'elle lançait ces tests au niveau
**classe**, ce qui marche parfaitement.

```
run_test .../UrlBuilderTest.java:32:testUrl        → Error: the test JVM failed ...
run_test .../UrlBuilderTest.java:8:UrlBuilderTest  → 20 test(s), 20 passed  ✔
```

Cause : `TestSelector.selector()` produit `--method Class#nomMethode`, sans
types de paramètres. `DiscoverySelectors.selectMethod(String)` interprète alors
l'absence de parenthèses comme « méthode sans argument » et lève
`PreconditionViolationException: Could not find method with name [testUrl] in
class [...]` → `TestRunnerMain.main` attrape le `Throwable` et sort en
`EXIT_BROKEN` (vérifié : code 3).

Pistes : émettre la signature complète `Class#method(java.lang.String,
boolean)` en lisant les types depuis jdtls (le symbole est déjà résolu) ; ou,
plus robuste, sélectionner la **classe** et filtrer le plan de test sur le nom
de méthode côté `TestRunnerMain` — ce qui couvre gratuitement les surcharges.

(`ProjectTests.report()` gère déjà correctement le cas « 0 test trouvé » ; ici
on ne l'atteint jamais, la discovery ayant explosé avant.)

**17. Le message d'échec de `run_test` est mangé par la bannière de la JVM.**
Symptôme du point 16, tel qu'il arrive au client :

```
Error: the test JVM failed to run the tests: Picked up JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStore=... [1400 caractères]
```

`ProjectTests.firstLine(stderr)` prend littéralement la première ligne de
stderr. Or quand `JAVA_TOOL_OPTIONS` est défini — sandbox Claude, CI,
conteneurs Docker d'entreprise — la JVM fille écrit d'abord `Picked up
JAVA_TOOL_OPTIONS: …`. **La vraie exception n'est jamais affichée**, et le
diagnostic devient impossible sans lire le source de clide.

Petit correctif, gros gain : ignorer les lignes de bruit JVM (`Picked up
JAVA_TOOL_OPTIONS`, `Picked up _JAVA_OPTIONS`, `OpenJDK ... VM warning`) avant
de choisir la ligne à rapporter ; mieux encore, rapporter la première ligne
ressemblant à une exception (`^[\w.]+(Exception|Error):`) ou le dernier
`Caused by:` — ici `PreconditionViolationException: Could not find method with
name [testUrl]`, qui aurait donné le diagnostic immédiatement.

> Note de méthode : j'ai d'abord cru à une **sortie vide**, donc à un bug bien
> plus grave. C'était mon propre `grep -v "Picked up JAVA_TOOL_OPTIONS"` qui
> mangeait la ligne ; le daemon, interrogé en socket brut, renvoyait bien le
> message. La leçon reste : ce message est indistinguable du bruit, y compris
> pour un filtre naïf.

**18. Un test *aborted* (assumption) est compté comme *failed*.**
`TestRunnerMain.Recorder.executionFinished` ne teste que `SUCCESSFUL` ; tout le
reste est compté `failed`. Or JUnit distingue `FAILED` de **`ABORTED`** — le
statut d'un `Assumptions.assumeTrue(...)` non satisfait, c'est-à-dire « test
volontairement non exécuté ».

```
Error: run_test: 1 test(s), 0 passed, 1 failed in 1147 ms
  [failed] .../InputFileUrlTest.java:38: testNewInputStream_containsTitle
     org.opentest4j.TestAbortedException: Assumption failed: Network unavailable, skipping test
```

Gradle, Maven et la console JUnit rapportent ce test comme **skipped, 0
failure**. PlantUML s'appuie beaucoup sur les assumptions (`allow-failure:
true` de Vega, gardes réseau) : 5 des 79 « échecs » du run complet sont dans ce
cas. Pour un agent qui utilise clide pour décider « est-ce que ma modif a cassé
quelque chose », c'est un faux positif qui coûte cher. `ABORTED` devrait
alimenter le compteur `skipped`, déjà présent.

**19. `jdtls/` (62 Mo) est extrait dans le répertoire courant.**
`ClideDaemon.jdtlsHome()` renvoie `Paths.get("jdtls")` — chemin **relatif**,
résolu contre le CWD du daemon. Lancer `clide .` ou `clide /chemin/projet`
**depuis** le projet y dépose donc un répertoire `jdtls/` de 62 Mo, non suivi
par git :

```
$ git status --short
?? jdtls/
$ du -sh jdtls
62M     jdtls
```

Ce qui contredit CLAUDE.md : « a `git status` on the opened project never shows
anything moving at its root because of clide ». (PlantUML `.gitignore` bien
`.project` et `.classpath` — mais pas `jdtls/`.) Effet secondaire mesuré : la
ré-extraction par CWD fait payer 62 Mo à chaque nouveau répertoire de
lancement — 2ᵉ démarrage à froid depuis un CWD différent : **77 s** contre
45 s. `CLIDE_JDTLS_HOME` permet de contourner, mais le défaut devrait être un
emplacement stable et partagé (`~/.clide/jdtls`, `$XDG_CACHE_HOME/clide/jdtls`).

**20. Les commandes de transaction sont documentées mais désactivées.**
CLAUDE.md consacre une section entière aux transactions (« The transaction
mechanism below exists and works ») avec 5 commandes et les règles des
sous-transactions imbriquées. Aucune n'est utilisable :
`open_transaction` → `?SYNTAX ERROR`. `Main.java` lignes 61-62 : les cinq sont
**commentées** dans la liste `commands`, donc absentes de `help` aussi. Le code
des commandes et `TransactionStack` existent bien. (C2 les testait encore —
elles ont été débranchées depuis.) Soit les réactiver, soit signaler dans
CLAUDE.md que la section décrit du code présent mais non branché.

### Points anciens revérifiés

- **Point 5** (`typeDefinition` vers un type JDK) : toujours ouvert, reproduit
  sur daemon neuf — mais l'échec est désormais **propre et clair**, ce que
  CLAUDE.md ne dit pas : `Error: find_declaration failed: No response for
  textDocument/typeDefinition (id=83) after 30s` en 30,2 s. Reste le coût des
  30 s, plus le message.
- **Point 6** (`@Help` de `man`) : toujours « please write help of man ».
- **Point 12** : `find_symbol DEFAULT_FONT_FAMILY` → rien, conforme.
- **Point 13** : confirmé, `rebuild` à 0 changement paie le build complet.
- **Limite « this repository holds N modules »** : ne s'est **jamais**
  déclenchée sur PlantUML — cohérent, PlantUML n'embarque pas ses propres
  fichiers Eclipse.
- **Propreté** : `.classpath` bien retiré après le build initial, mais
  **`.project` survit tant que le daemon tourne** et n'est retiré qu'au
  `terminate` — la formulation « erased once the initial build finishes » de
  CLAUDE.md est inexacte. Sans effet sur `git status` ici (PlantUML l'ignore).
  `bin/` (sortie de compilation jdtls) est également déposé à la racine, lui
  aussi gitignoré par PlantUML. Après `terminate` et suppression de `jdtls/`,
  `git status` est propre.

### Conclusion

Ce qui est annoncé comme prioritaire dans CLAUDE.md fonctionne, dans l'ordre
annoncé : compiler et obtenir les erreurs exactes (excellent, y compris sur
refactor cassé à 47 sites), requêtes sémantiques (exactes là où `grep` produit
des faux positifs), lancer un test ciblé — **partiellement cassé, point 16**.

Ordre de correction proposé : 16 (bloque `run_test` sur une large part des
suites Java modernes), 17 (deux lignes, transforme un message inutilisable en
diagnostic immédiat), 18 (faux « échecs » sur tout projet utilisant
`Assumptions`), 19 (62 Mo déposés dans le dépôt de l'utilisateur, contre une
promesse explicite), 20 (documentation à réaligner sur le code).

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
| 15 | `run_tests` (suite complète, 259 classes PlantUML) : jamais terminé dans les 10 min du sandbox, probablement `graphviz`/`dot` manquant | C4 | **levé** (C5 : 3063 tests en 28 s ; avertissement à retirer de CLAUDE.md) |
| 16 | `run_test` sur une méthode à paramètres (tout `@ParameterizedTest`) échoue : `TestSelector` émet `Class#method` sans types, JUnit ne résout qu'une méthode sans argument → `EXIT_BROKEN` | C5 | ouvert (**priorité 1**) |
| 17 | `ProjectTests.firstLine(stderr)` rapporte la bannière `Picked up JAVA_TOOL_OPTIONS:` au lieu de l'exception — diagnostic impossible dès que la variable est définie (CI, Docker, sandbox) | C5 | ouvert (**priorité 2**, correctif trivial) |
| 18 | Un test `ABORTED` (assumption non satisfaite) est compté `failed` au lieu de `skipped` — 5 faux échecs sur la suite PlantUML | C5 | ouvert |
| 19 | `jdtls/` (62 Mo) extrait dans le CWD (`Paths.get("jdtls")`) : pollue le projet si clide est lancé depuis sa racine, contre la promesse « rien ne bouge à la racine » | C5 | ouvert |
| 20 | Commandes de transaction documentées dans CLAUDE.md mais commentées dans `Main.java` (lignes 61-62) → `?SYNTAX ERROR`, absentes de `help` | C5 | ouvert (doc vs code) |
| 21 | CLAUDE.md : `rebuild` annoncé « 9 à 12 s », mesuré **14 à 21 s** sur PlantUML ; `.project` annoncé effacé après le build initial, en fait retiré seulement au `terminate` | C5 | ouvert (mineur, doc) |
