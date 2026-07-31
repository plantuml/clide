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

## État des points

| # | Point | Origine | Statut |
|---|---|---|---|
| 1 | `find_implementation` et overrides par érasure | C1 | **corrigé** (C2, passe nom+arité) |
| 2 | `search_regex` chemin relatif → 0 silencieux | C1 | ouvert (en tête de TODO.md) |
| 3 | Sorties `search_regex` en absolu (vs relatif ailleurs) | C1 | ouvert (idem TODO.md) |
| 4 | Bruit de protocole en usage batch | C1 | **corrigé** (C2, `PrintMode.AI`) |
| 5 | `typeDefinition` vers un type JDK : aucune réponse, timeout 30 s | C2 | ouvert |
| 6 | `@Help` de `man` : placeholder « please write help of man » | C2 | ouvert (mineur) |
| 7 | Commande `build`/rebuild + diagnostics après édition | C1+C2 | ouvert — **priorité n°1** |
| 8 | Lancement d'un test ciblé | C1 | ouvert |
| 9 | Call hierarchy (jdtls le supporte) | C1 | ouvert |
| 10 | Type hierarchy structurée / super-types | C1 | ouvert |
| 11 | `list_members` avec membres hérités (option) | C1 | ouvert (souhait) |
| 12 | Recherche de champs par nom | C1 | limite jdtls, non actionnable |
