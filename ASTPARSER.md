# ASTPARSER.md — Recherches à grain fin via `ASTParser` JDT en batch

Discussion 2026-08-02, pure réflexion/architecture — rien d'implémenté.
Reprend l'idée n°5 de `JAVALENSE.md` (« Recherches à grain fin — et la
cartographie des trois niveaux »), déjà validée par un prototype
(`Proto.java`), et la développe côté commandes clide concrètes — en gardant
à l'esprit qu'on veut aussi les exposer en Lua (voir `LUA.md`), donc en
pensant leur forme de retour dès la conception plutôt qu'en rustine après
coup.

## Pourquoi ce chantier

`clide` s'appuie aujourd'hui uniquement sur jdtls (façade LSP au-dessus de
JDT). C'est solide pour les trois priorités fondatrices du projet — build +
diagnostics, requête ciblée, navigation sémantique — mais le protocole LSP
n'expose pas tout ce que le moteur JDT sait réellement faire. Neuf types de
recherche identifiés dans `javalens-mcp` s'appuient sur des constantes de
`SearchEngine` que LSP ne relaie pas : casts vers un type, `instanceof`,
`new Type()`, `throws`, blocs `catch`, `Type::method`, arguments génériques
(`List<Type>`), usages d'annotation, et surtout la distinction **lecture vs
écriture de champ** — « qui mute cet état ? » est vraisemblablement la plus
utile des neuf pour un agent qui modifie du code.

Le point fort par rapport à un grand écart vers Equinox/workspace (le
niveau 3, celui que `javalens-mcp` a choisi et qui coûte ~5200 lignes de
socle avant le premier outil) : ce chantier **ne coûte aucune dépendance
supplémentaire**. Les jars nécessaires sont déjà présents dans l'archive
`jdt-language-server-latest.zip`, déjà commitée pour jdtls. Et le prototype
déjà réalisé (`Proto.java`, ~250 lignes, zéro dépendance hors ces jars) a
mesuré des temps compatibles avec un usage interactif.

## Rappel : la cartographie à trois niveaux (`JAVALENSE.md`)

1. **jdtls (le socle actuel, inchangé)** : build + diagnostics, navigation
   indexée, call hierarchy. Couvre entièrement les trois priorités
   fondatrices de clide — ce chantier ne remplace rien de ce qui existe.
2. **`org.eclipse.jdt.core` en jar ordinaire, `ASTParser` en mode batch** —
   sans workspace ni OSGi. C'est le sujet de ce document.
3. **Le grand saut Equinox/workspace** (ce que fait `javalens-mcp`) :
   `SearchEngine` indexé à grain fin + moteur de refactoring, au prix de
   toute la plomberie que jdtls nous évite aujourd'hui. À éviter sauf
   besoin massif et démontré — non retenu ici.

## Le prototype déjà validé

Recette technique (`Proto.java`) : `ASTParser` en mode batch, standalone.

- **Aucun jar supplémentaire à committer** : 16 jars suffisent (~11 Mo),
  tous déjà présents dans `jdt-language-server-latest.zip` (répertoire
  `plugins/`) — `org.eclipse.jdt.core`, `org.eclipse.osgi`,
  `org.eclipse.core.{resources,runtime,jobs,contenttype,filesystem,
  expressions,commands}`, `org.eclipse.equinox.{common,preferences,
  registry,app}`, `org.eclipse.text`, `org.eclipse.jdt.core.compiler.batch`,
  et `org.osgi.service.prefs` (piège relevé dans `JAVALENSE.md` : son
  absence donne un `NoClassDefFoundError` dès `ASTParser.newParser()`, pas
  un message qui pointe vers la vraie cause).
- Recette : `setResolveBindings(true)` + `setBindingsRecovery(true)` +
  `setEnvironment(classpath, sourceRoots, null, true)` + `createASTs()`
  avec un `FileASTRequestor`, par lots de 500 fichiers (le parser est à
  usage unique, à reconfigurer à chaque lot).
- **Mesures sur PlantUML** (clone frais, 3601 fichiers, JDK 21, sandbox
  Claude) : balayage complet avec bindings résolus **22,3 s** (6,2
  ms/fichier) ; reparse d'un seul fichier **77 ms** ; pic mémoire heap
  **791 Mo** (AST visités puis relâchés lot par lot, jamais tous retenus
  en mémoire).
- Deux requêtes démontrées avec succès sur ce balayage : `find_field_writes`
  sur `TitledDiagram#useSmetana` distingue correctement la seule vraie
  écriture (le paramètre homonyme du setter n'est pas compté à tort — un
  grep ne saurait pas faire cette distinction) ; `find_casts` vers
  `net.sourceforge.plantuml.abel.Entity` trouve 18 casts exacts dans 8
  fichiers, résolus sémantiquement à travers les imports de chaque
  fichier. Au passage, le même balayage classe 64 420 lectures et 8 987
  écritures de champs sur tout le projet.

**Conclusion déjà actée dans `JAVALENSE.md`** : le niveau 2 est validé et
bon marché. Modèle d'usage réaliste : un balayage complet au premier
besoin (~22 s, cachable jusqu'au prochain rebuild), réparation incrémentale
à 77 ms/fichier édité ensuite — ou, plus simple pour une première version,
un balayage à la demande pour des requêtes ponctuelles, sans cache du tout.

## Nouvelles commandes envisagées

Toutes suivent la convention déjà en place pour les commandes `find_*`
(paramètre `<symbole>` en notation `<chemin fichier>:<ligne>:<nom>` quand
la requête part d'une occurrence précise, format de sortie `chemin/
relatif.java:ligne: contenu de la ligne` recopiable dans la commande
suivante).

**`find_field_usage <what> <symbole>`** — lecture/écriture/les deux d'un
champ. `<what>` ∈ `read`/`write`/`all`, dans le même esprit que le `<what>`
de `find_declaration` (`method`/`type`) — sauf qu'ici `<what>` change
réellement le comportement (contrairement à `find_reference`/
`find_implementation`, où il est resté cosmétique par symétrie). Le cas
`write` est probablement le plus utile en pratique : « qui mute cet état ? »
n'a aucun équivalent dans les commandes actuelles. Point de vigilance
signalé par le prototype : le nom du champ dans sa propre déclaration
compte comme une lecture à corriger — équivalent du `includeDeclaration:
false` déjà appliqué à `find_reference`.

**Un groupe de huit requêtes « quel genre d'usage de ce type ? »** — casts,
`instanceof`, `new Type()`, `throws`, `catch`, `Type::method`, arguments
génériques, usages d'annotation. Deux façons de les exposer, à trancher :

- huit commandes dédiées (`find_casts`, `find_instanceof`,
  `find_constructions`, `find_throws`, `find_catches`,
  `find_method_references`, `find_generic_usages`,
  `find_annotation_usages`), chacune prenant `<symbole>` (un type) ;
- **ou** une seule commande unifiée, `find_type_usage <kind> <symbole>`,
  `<kind>` énumérant les huit natures de recherche — sur le même principe
  que la harmonisation déjà faite pour `find_declaration`/`find_reference`/
  `find_implementation` (qui a remplacé quatre `goto_*` disparates par
  trois commandes avec un paramètre `<what>`), documentée dans `CLAUDE.md`.

La deuxième option paraît plus cohérente avec le goût déjà démontré dans le
projet pour ce genre de regroupement, et évite huit entrées quasi
identiques dans `help`/`help_ai` — mais c'est un choix à confirmer, pas
acté ici. Dans les deux cas, `<symbole>` désigne ici un **type**, pas une
position d'usage — la notation `<chemin>:<ligne>:<nom>` reste pertinente
(le type est déclaré quelque part), mais la question posée est différente
de `find_reference` (« qui référence cette déclaration » vs « qui utilise
ce type de cette façon syntaxique particulière »).

**Commandes appuyées sur le graphe whole-program** (idée n°4 de
`JAVALENSE.md`, un niveau au-dessus du balayage AST brut — un graphe
types/méthodes/champs avec arêtes `CALLS`/`CREATES`/`READS`/`WRITES`,
construit paresseusement à partir du même balayage, mis en cache jusqu'au
prochain build) :

- `find_affected_tests <symbole>` — les tests qui exercent ce symbole,
  transitivement, à travers les helpers non-test et le dispatch
  d'interface. Rejoint directement la priorité n°2 de clide (« lancer un
  test ciblé ») par l'autre bout : elle répond à *quel* test lancer après
  une modification, alors que `run_test` répond à *comment* le lancer une
  fois qu'on sait lequel.
- `find_unreachable_code <chemin>` — code mort inatteignable depuis tout
  `main`/test. À documenter avec la même honnêteté que `javalens-mcp` :
  les points d'entrée réflexion/DI sont invisibles au graphe, donc
  « inatteignable » ne veut pas dire « supprimable sans vérification ».
- `analyze_change_impact <symbole>` — rayon d'impact transitif d'un
  changement (fermeture inverse sur le graphe, à travers les overrides).
  **Piège à ne pas reproduire**, corrigé dans `javalens-mcp` en 1.5.1
  (#32) : la fermeture inverse partant d'un *type* doit agréger les
  appelants du type **et de tous ses membres** (constructeurs, méthodes,
  champs), pas seulement du nœud type lui-même — sinon le rayon d'impact
  rapporté est incomplet.

Ces trois commandes ont une forme de retour différente des `find_*`
existantes (pas une simple liste de locations : un ensemble de tests, un
ensemble de symboles morts, un graphe de dépendances) — à concevoir
séparément côté `CommandData` plutôt que de forcer la même forme que
`find_field_usage`/`find_type_usage`.

## Modèle d'usage : où ça s'articule avec le daemon existant

Le jdtls du projet démarre et build automatiquement dès le premier `clide
<chemin>` — aucune commande séparée à taper pour l'ouvrir. Question à
trancher pour le niveau 2 : le balayage AST doit-il suivre le même
principe (lazy, déclenché par la première commande de ce groupe, puis mis
en cache), plutôt qu'un balayage systématique au démarrage du daemon (qui
ajouterait ~22 s à chaque ouverture de projet, même si aucune commande de
ce groupe n'est jamais utilisée) ? Le lazy semble cohérent avec l'esprit
actuel de clide (le jdtls lui-même ne bloque le démarrage que pour son
propre build, pas pour des capacités non sollicitées).

Deuxième question : le `rebuild` existant (recompilation via jdtls) doit-il
aussi invalider/refaire le cache AST, ou ce cache a-t-il son propre cycle
de vie (invalidation seulement quand une commande du groupe la sollicite à
nouveau après une modification connue) ? Vu le coût asymétrique (22 s pour
un balayage complet contre 77 ms pour un reparse d'un seul fichier), un
couplage fin — invalider seulement les fichiers effectivement modifiés,
plutôt que tout le cache — vaut largement l'effort.

Ça rejoint directement l'idée n°1 de `JAVALENSE.md` (`DiskStampService`,
déjà discutée comme angle mort de clide dans `LUA.md`) : la même mécanique
de détection de fichiers modifiés depuis le dernier état connu servirait
aussi bien à rafraîchir le modèle jdtls qu'à invalider le cache AST de
façon ciblée — un seul mécanisme de staleness, deux consommateurs.

## En pensant Lua dès la conception

`LUA.md` propose de faire évoluer `CommandResult` vers une enveloppe
`status`/`message`/`data` (structuré, `CommandData` scellée)/`error{code,
hint}`/`meta{totalCount, returnedCount, truncated}`. Ce chantier est un bon
test de charge pour cette enveloppe, pour deux raisons :

- **Les volumes peuvent être grands** — 64 420 lectures et 8 987 écritures
  de champs sur l'ensemble de PlantUML dans le prototype. Un script Lua qui
  boucle sur un `find_field_usage all <ChampTrèsUtilisé>` sans plafond a de
  quoi saturer la mémoire du script ou simplement être inexploitable. Ces
  commandes devraient donc avoir `meta.totalCount`/`meta.truncated`
  corrects dès leur première version, pas ajoutés après coup — et
  probablement un paramètre `max_results` honoré littéralement (négatif →
  erreur nommée, zéro → zéro résultat sans clamp silencieux), comme
  `JAVALENSE.md` le recommande pour ses propres outils.
- **Les formes de retour sont hétérogènes** (liste de locations pour les
  huit requêtes de type, mais graphe/ensemble pour `find_affected_tests`/
  `find_unreachable_code`/`analyze_change_impact`) — c'est exactement le
  genre de diversité qui pousse vers une interface scellée `CommandData` à
  plusieurs implémentations plutôt qu'un seul type de payload générique
  (voir `LUA.md`, section `CommandResult`). Concevoir ces commandes force à
  lister concrètement combien de formes de `CommandData` sont réellement
  nécessaires, plutôt que de le deviner dans l'abstrait.

Autrement dit : ce chantier ASTParser et le chantier Lua (`LUA.md`) se
nourrissent mutuellement — les nouvelles commandes ici sont un bon
prétexte pour concevoir `CommandData`/`CommandResult` sur un cas réel
plutôt que sur les commandes `find_*` existantes seules, qui ont toutes la
même forme de retour (liste de locations) et cacheraient donc une partie
des questions de conception.

## Pièges JDT à garder en tête pour cette implémentation

Repris du catalogue de `JAVALENSE.md` (idée n°7), en filtrant ceux
pertinents pour un `ASTParser` batch plutôt qu'un `SearchEngine` indexé :

- **Compliance hors plage = cécité silencieuse.** Un niveau de compilateur
  non supporté (trop ancien ou plus récent que le JDT embarqué) fait que
  JDT ne produit aucun diagnostic — projet « propre » quel que soit son
  état réel. `ASTParser` partage le même compilateur JDT que jdtls ; le
  même risque s'applique. Le correctif de `javalens-mcp` (clamper dans la
  plage supportée, avertir en nommant les deux niveaux) est transposable
  tel quel.
- **Positions dans littéraux/commentaires.** Les outils positionnels de
  `javalens-mcp` renvoyaient à tort le membre englobant pour une position
  dans une chaîne ou un commentaire. La résolution `\bnom\b` sur la ligne
  déjà utilisée par `Position` immunise en partie clide côté commandes qui
  partent d'une position ; les nouvelles commandes qui partent d'un
  *type* plutôt que d'une position (la plupart de ce groupe) n'ont pas ce
  problème par construction — mais celles qui rendraient un résultat sous
  forme de position (chaque cast, chaque `catch`) doivent refuser
  proprement une position invalide plutôt que de répondre à côté.
- **Course d'indexation** : ne s'applique pas ici — `ASTParser` en mode
  batch est synchrone, pas d'indexeur asynchrone en arrière-plan à
  attendre. C'est un avantage structurel de ce niveau par rapport au
  niveau 3 (`SearchEngine` indexé), à noter explicitement comme argument
  en faveur du choix déjà fait d'éviter l'Equinox/workspace complet.
- **Snapshots-tests dégénérés.** Rappel de la discipline déjà en place
  dans clide (tests hors dépôt, valeurs attendues dérivées à la main de la
  fixture — voir `CLAUDE.md`, « Tests unitaires de clide ») : ne pas
  capturer la sortie d'un premier prototype comme oracle, construire les
  attendus indépendamment (ex. compter les casts/écritures à la main sur
  un petit projet cobaye, pas en relisant la sortie de `Proto.java`).

## Questions ouvertes

- Huit commandes dédiées vs une commande unifiée `find_type_usage <kind>
  <symbole>` pour le groupe casts/`instanceof`/constructions/`throws`/
  `catch`/méthode-référence/générique/annotation.
- Balayage lazy (déclenché par la première commande du groupe) vs
  systématique au démarrage du daemon.
- Le cache AST partage-t-il son invalidation avec `rebuild`/un futur
  `DiskStampService`, ou a-t-il un cycle de vie propre ?
- `find_affected_tests`/`find_unreachable_code`/`analyze_change_impact`
  méritent-elles d'être construites maintenant (elles demandent le graphe
  whole-program, une couche au-dessus du balayage brut), ou seulement une
  fois les huit requêtes de type/champ en place et éprouvées ?
- Faut-il un paramètre `max_results` dès la première version de ces
  commandes, vu les volumes observés (dizaines de milliers d'usages
  possibles sur un projet de la taille de PlantUML) ?

## Prochaines étapes envisagées (non implémentées)

- Trancher commandes dédiées vs commande unifiée pour le groupe de type.
- Étendre `Proto.java` (déjà dans le dépôt) d'un prototype jetable vers une
  première commande réelle — `find_field_usage` est le candidat naturel
  (déjà démontré dans le prototype, la plus directement utile d'après
  `JAVALENSE.md`).
- Décider du modèle de cache (lazy + invalidation ciblée par fichier) avant
  d'écrire plus d'une commande, pour ne pas avoir à le refaire huit fois.
- Concevoir cette première commande main dans la main avec sa forme
  `CommandData` (voir `LUA.md`), pour valider l'enveloppe `CommandResult`
  sur un cas dont la forme de retour n'est pas juste « une liste de
  locations » déguisée.
