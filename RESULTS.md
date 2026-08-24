# RESULTS.md — Format des réponses de clide

Référence des packages `clide.command.answer` (l'enveloppe et le payload
propres à une réponse de commande) et `clide.model` (les types de domaine
génériques - localisations, symboles, diagnostics... - que ces payloads
référencent) : ce que contient une réponse de commande, champ par champ, et le
texte que chaque forme produit.

Complète les autres documents plutôt qu'il ne les remplace : `CLAUDE.md`
décrit **comment utiliser** clide (quelle commande poser pour quelle
question), `CODING.md` les conventions d'écriture du code, et ce fichier-ci la
**structure des réponses** — pour qui écrit une commande, un handler, ou un
client qui lit la sortie.

Le principe tient en une phrase : **le payload est la vérité, le texte en est
une vue**. Une commande produit des données typées ; le texte est fabriqué
ensuite par `Command.render()`, et par rien d'autre. C'est ce qui permet de
compter, de plafonner et de brancher sur une cause sans jamais reparser la
sortie de clide.

## Vue d'ensemble : l'imbrication

```
CommandResult                          l'enveloppe, identique pour toute commande
├── status        CommandStatus        OK | ERROR
├── code          ErrorCode            NONE ssi status == OK
├── message       String               une ligne, obligatoire si ERROR
├── hint          String               souvent vide - voir CODING.md
├── warnings      List<Warning>        peut être non vide sur un OK
│   └── Warning
│       ├── code      WarningCode
│       └── message   String
└── payload       CommandPayload       jamais null - NOTHING s'il n'y a rien à dire
    │
    ├── Nothing                        (aucun champ)
    ├── Text            text
    ├── Locations       subject, locations ─────► Listing<CodeLocation>
    ├── Symbols         subject, symbols ───────► Listing<SymbolHit>
    ├── SearchMatches   matches ───────────────► Listing<SearchMatch>
    │                   fileCount
    ├── Diagnostics     report ────────────────► DiagnosticsReport
    │                                              └── diagnostics ► Listing<Diagnostic>
    ├── Rebuild         changedFiles, elapsedMillis, report ► DiagnosticsReport
    ├── TestRun         subject, passed, failed, skipped,
    │                   elapsedMillis, failuresOnly,
    │                   tests ─────────────────► Listing<TestOutcome>
    ├── Transaction     id, action, path
    ├── ModifiedFiles   transactionId, files ──► Listing<String>
    ├── Diff            transactionId, path, unifiedDiff
    ├── CommandList     commands ──────────────► Listing<CommandSummary>
    └── Setting         name, previousValue, newValue

Listing<T>                             partout où une réponse est une liste
├── items         List<T>              plafonnée
├── totalCount    int                  le vrai total, compté avant plafonnement
└── maxResults    int                  le plafond appliqué
```

Deux règles de placement expliquent la forme ci-dessus, et méritent d'être
comprises avant d'ajouter quoi que ce soit :

- **`totalCount`/`truncated` vivent dans `Listing`, pas dans l'enveloppe.** Ils
  n'ont de sens que pour une commande qui répond par une liste. `hover` répond
  par un bloc de texte, `open_transaction` par rien : les hisser au niveau de
  `CommandResult` forcerait ces deux-là à répondre à une question qui ne se
  pose pas pour elles — et c'est exactement comme ça qu'un champ finit par
  contenir un mensonge plausible.
- **Un payload par *forme* de résultat, pas par commande.** `find_declaration`,
  `find_reference` et `find_implementation` répondent tous les trois par une
  liste de localisations : ils partagent `Locations` au lieu d'avoir chacun un
  record quasi identique.

---

## `CommandResult` — l'enveloppe

| Champ | Type | Toujours présent | Rôle |
|---|---|---|---|
| `status` | `CommandStatus` | oui | `OK` (la commande a répondu) ou `ERROR` (elle a refusé). Binaire, jamais un troisième état. |
| `code` | `ErrorCode` | oui | Pourquoi elle a refusé. Vaut `NONE` **exactement quand** `status == OK`, si bien qu'un appelant n'a jamais à vérifier les deux. |
| `message` | `String` | oui (peut être vide) | Une ligne, pour un humain. **Obligatoire** sur une erreur — une erreur muette est refusée à la construction. Inutilisé en succès : c'est le payload qui porte tout, et `render()` qui écrit les mots. |
| `hint` | `String` | oui (souvent vide) | La chose à faire ensuite, quand il y en a une. Vide est le cas normal — voir `CODING.md` pour ce qu'un hint a le droit d'affirmer. |
| `warnings` | `List<Warning>` | oui (souvent vide) | Peut être non vide sur un résultat `OK`. |
| `payload` | `CommandPayload` | oui | `CommandPayload.NOTHING` quand il n'y a rien à rapporter, jamais `null`. |

Invariants imposés par le constructeur compact — toute violation lève
`IllegalArgumentException` à la construction, pas plus tard :

| Règle | Pourquoi |
|---|---|
| `status == OK` ⟹ `code == NONE` | Un succès ne peut pas porter une cause d'échec. |
| `status == ERROR` ⟹ `code != NONE` | `NONE` n'est pas une raison ; une erreur doit se nommer. |
| `status == ERROR` ⟹ `message` non vide | Une erreur qui ne dit rien n'en est pas une. |
| aucun champ `null` | Un absent s'écrit `""`, `List.of()` ou `NOTHING`. |

Fabriques :

| Appel | Produit |
|---|---|
| `CommandResult.ok(payload)` | `OK`, `NONE`, message et hint vides |
| `CommandResult.empty()` | `ok(CommandPayload.NOTHING)` |
| `CommandResult.error(code, message)` | `ERROR`, sans hint ni payload |
| `CommandResult.error(code, message, hint)` | idem, avec hint |
| `CommandResult.error(code, message, hint, payload)` | erreur **qui a tout de même quelque chose à montrer** — le seul usage réel est `run_tests`, qui liste ses échecs sous l'en-tête d'erreur |
| `.withHint(h)`, `.withWarning(w)`, `.withWarnings(ws)` | rendent une copie ; l'original est intact |

**Ne rien trouver n'est pas une erreur.** `find_reference` sans aucun usage,
`list_members` sur un type sans membre, `search_regex` sans correspondance :
tous répondent `OK` avec une liste vide, et le disent en toutes lettres. Seule
une question à laquelle clide n'a pas pu répondre du tout donne un `ERROR`.

---

## La forme rendue : trois shapes, et seulement trois

`ResultEnvelope` fabrique l'enveloppe, `Command.render()` le corps. Un client
n'a que deux marqueurs à reconnaître :

```
?ERROR NAME_NOT_AT_COLUMN: 'add' does not start at column 10 of line 12 of Foo.java
hint: 'add' starts at columns 14, 25 on that line
!WARNING TRANSACTIONS_STILL_OPEN: $refactor_foo
```

| Élément | Forme | Quand |
|---|---|---|
| en-tête d'erreur | `?ERROR <CODE>: <message>` | `status == ERROR` |
| hint | `hint: <texte>` sur la ligne suivante | `hint` non vide |
| corps | ce que rend `Command.render()` | payload non vide — y compris sous un en-tête d'erreur |
| avertissement | `!WARNING <CODE>: <message>`, un par ligne, après le corps | pour chaque `Warning` |

Un succès n'a **pas d'en-tête** : le corps est la réponse, et préfixer chaque
réponse réussie d'un « OK » décoratif ne ferait qu'ajouter une chose à
retirer. Une ligne par fait, rien de replié ni d'aligné : la même sortie
alimente un humain devant un terminal et un programme derrière une socket, et
c'est le second qui casse quand un message passe sur deux lignes.

Quand tout est vide, rien n'est écrit du tout.

---

## `ErrorCode`

Un code se justifie s'il nomme un échec auquel un appelant réagirait
différemment. Deux échecs qui appellent toujours le même geste partagent un
code : une taxonomie plus fine que ce que clide sait réellement distinguer est
un mensonge, pas un service.

| Groupe | Codes |
|---|---|
| aucune erreur | `NONE` |
| protocole | `UNKNOWN_KEYWORD`, `MISSING_PARAMETERS` |
| paramètres | `INVALID_ENUM_VALUE`, `EMPTY_PARAMETER`, `INVALID_REGEX`, `INVALID_INTEGER`, `VALUE_OUT_OF_RANGE`, `INVALID_TRANSACTION_ID`, `NOT_A_DIRECTORY` |
| `<position>` | `MALFORMED_POSITION`, `FILE_NOT_FOUND`, `FILE_UNREADABLE`, `FILE_MODIFIED`, `LINE_OUT_OF_RANGE`, `NAME_NOT_ON_LINE`, `NAME_NOT_AT_COLUMN`, `NOT_A_TYPE`, `NOT_A_METHOD`, `SYMBOL_NOT_FOUND`, `AMBIGUOUS_SYMBOL` |
| jdtls | `SESSION_START_FAILED`, `JDTLS_REQUEST_FAILED`, `BUILD_FAILED` |
| transactions | `NO_OPEN_TRANSACTION`, `TRANSACTION_REFUSED`, `TRANSACTION_IO_FAILED`, `TERMINATE_REFUSED` |
| tests | `TEST_FAILURES`, `NO_TEST_FOUND`, `TEST_CLASS_NOT_COMPILED`, `TEST_RUNNER_BROKEN`, `TEST_TIMEOUT`, `NO_OUTPUT_FOLDER`, `CLASSPATH_UNAVAILABLE`, `MULTI_MODULE_PROJECT` |
| divers | `IO_FAILED` |

Les codes de `<position>` sortent tous de `PositionParser.parse()`, qui les
porte via `PositionException` — laquelle reste une `IllegalArgumentException`,
si bien que tout `catch` écrit avant l'existence des codes fonctionne
inchangé. Les six premiers (`MALFORMED_POSITION` à `NOT_A_TYPE`) viennent de
la notation canonique et sont hors ligne ; `SYMBOL_NOT_FOUND` et
`AMBIGUOUS_SYMBOL` viennent des trois notations SYMBOLS.md ajoutées par la
suite (`Classe::membre`, `Classe`/`Outer.Inner` seule, `NomFichier.java`) —
un code par issue (zéro candidat / plusieurs) plutôt qu'un par grammaire,
puisque le geste de l'appelant est le même quelle que soit la notation en
cause.

`NAME_NOT_ON_LINE` et `NAME_NOT_AT_COLUMN` se ressemblent et ne se corrigent pas
pareil, d'où deux codes plutôt qu'un : le premier dit que le nom n'est **nulle
part** sur la ligne (mauvaise ligne, ou fichier périmé — changer de colonne n'y
ferait rien) ; le second qu'il est bien sur la ligne, mais ailleurs, et son
`hint` donne les colonnes où il commence réellement.

`FILE_MODIFIED` est le troisième de cette famille, et le seul qui ne se corrige
pas du tout dans le token lui-même : le `<file-content-md5>` n'est plus celui du
fichier, donc le fichier a changé depuis que la position a été produite, et il
n'y a rien à rectifier dans ce token — il faut redemander la position. Il est
vérifié **avant** la ligne, la colonne et le nom, précisément parce que ceux-ci
diraient un symptôme (`NAME_NOT_AT_COLUMN`) au lieu de la cause, ou
passeraient par accident. Il ne porte volontairement jamais le md5 courant
dans son `hint` : ce serait livrer le contournement avec l'erreur, puisqu'un
client pourrait le recoller tel quel dans le token et faire taire le contrôle
sans jamais avoir revérifié la ligne, la colonne ou le nom.

Un `hint` peut néanmoins apparaître, et c'est autre chose : une `<position>`
fraîche et complète, entièrement re-dérivée pour le même nom, offerte
seulement quand clide a une vraie preuve — la ligne exacte que visait l'ancien
token, relue telle quelle depuis une révision historique mise en cache, existe
encore mot pour mot quelque part dans le fichier actuel (voir
`PositionParser.staleHint()` et `Md5Repository.md5WithPrefix()`). Cette preuve
manque souvent (n'importe quelle modification de la ligne elle-même la fait
échouer, et la révision historique n'est en cache que si un `rebuild` a tourné
pendant qu'elle était encore la version courante) — la plupart des
`FILE_MODIFIED` restent donc sans hint, mais quand il y en a un, c'est une
position fraîche déjà vérifiée, pas un moyen de contourner le contrôle.

Rappel de l'asymétrie de la notation : le `<file-content-md5>` est **facultatif
en entrée** (l'omettre vaut « sur le fichier actuellement sur le disque », donc
sans ce contrôle) et **toujours présent en sortie**.

**Un code est délibérément absent : `STALE_MODEL`.** clide ne détecte pas
aujourd'hui qu'un modèle jdtls est plus vieux que les fichiers (seul `rebuild`
rafraîchit, sur demande explicite — voir `CLAUDE.md`). Un code jamais levé
annoncerait une garantie qui n'existe pas ; il aura sa place le jour où la
vérification existera, pas avant.

---

## `Warning` / `WarningCode`

| Champ | Type | Rôle |
|---|---|---|
| `code` | `WarningCode` | de quoi il s'agit |
| `message` | `String` | non vide — un avertissement muet est refusé |

Un avertissement **ne change jamais le statut** : la réponse tient, et le
client est libre de l'ignorer. C'est précisément ce qui permet à
`CommandStatus` de rester binaire ; un troisième état `OK_WITH_WARNINGS`
serait la garantie qu'un `if (status == OK)` écrit avant lui rate le cas
dégradé.

| Code | Levé par | Ce qu'il dit |
|---|---|---|
| `TRANSACTIONS_STILL_OPEN` | `exit`, `quit` | Des transactions survivent, intactes, pour la prochaine connexion. Purement informatif, rien n'est bloqué. |

`AMBIGUOUS_NAME_ON_LINE` a été **supprimé** avec le passage à la notation
`chemin:ligne:colonne:nom` : il signalait qu'un nom apparaissait plusieurs fois
sur sa ligne et que clide avait répondu sur la première occurrence. La colonne
étant désormais obligatoire, il n'y a plus de première occurrence à choisir —
chaque occurrence a sa propre colonne, et une colonne fausse est refusée
(`NAME_NOT_AT_COLUMN`) plutôt que rattrapée. Conforme au principe cardinal de
`SYMBOLS.md` : toute ambiguïté produit une erreur explicite, jamais une
résolution silencieuse.

**Exemple**

```
find_reference: 3 location(s)
src/main/java/demo/Calc.java:12:10:add return add(add(a, 1), add(a, 2));
src/main/java/demo/Calc.java:12:14:add return add(add(a, 1), add(a, 2));
src/main/java/demo/Calc.java:12:25:add return add(add(a, 1), add(a, 2));
```

---

## `Listing<T>` — le plafonnement

Composé par tout payload dont la réponse est une liste.

| Champ / méthode | Type | Rôle |
|---|---|---|
| `items` | `List<T>` | les entrées effectivement rendues, plafonnées, non modifiable |
| `totalCount` | `int` | le **vrai** total, compté avant plafonnement |
| `maxResults` | `int` | le plafond appliqué (voir `set_max_results`) |
| `returnedCount()` | `int` | `items.size()` |
| `truncated()` | `boolean` | `totalCount > items.size()` |
| `isEmpty()` | `boolean` | |
| `summarize(nom)` | `String` | la phrase de comptage, voir ci-dessous |

**Plafonner au formatage, compter à la source.** `Listing.of(tout, maxResults)`
reçoit la liste **complète** et la plafonne ici : `totalCount` est donc exact
et `truncated()` en est dérivé plutôt que deviné. Deux conséquences :

- un résultat de **exactement `maxResults`** entrées n'est **pas** tronqué — il
  n'y avait rien de plus. Déduire la troncature d'un « a-t-on atteint le
  plafond » est la façon classique d'annoncer incomplète une réponse complète ;
- un producteur **ne doit pas s'arrêter en route**. Ce qui construit la liste
  doit aller jusqu'au bout avant de la passer, sinon `totalCount` décrit
  jusqu'où on est allé et non ce qui existe.

`maxResults = 0` est honoré littéralement : zéro entrée rendue, total toujours
exact, troncature signalée dès qu'il y avait quelque chose. Aucun recalage
silencieux à 1.

**`summarize(nom)`**

| Situation | Rendu |
|---|---|
| non tronqué | `3 location(s)` |
| tronqué | `50 location(s) shown out of 312, truncated - raise the limit with set_max_results` |

---

## Les briques de données

### `CodeLocation`

Un endroit précis du projet, tel que le nomment tous les `find_*`,
`list_members` et `find_symbol`.

| Champ | Type | Rôle |
|---|---|---|
| `md5` | `String` | signature md5 du contenu **entier** du fichier, 8 caractères hexadécimaux minuscules (les 8 premiers des 32 que produit l'algorithme - jamais recherchée, seulement comparée) ; `null` si la position n'a pas pu être signée |
| `path` | `String` | chemin relatif à la racine du projet, séparateurs `/` |
| `line` | `int` | ligne 1-based ; `-1` si la réponse ne portait aucune plage exploitable |
| `column` | `int` | colonne 1-based du début du symbole ; `-1` dans le même cas |
| `name` | `String` | le nom du symbole à cet endroit ; `""` si la ligne n'a pas pu être relue, ou si la colonne ne commence pas un mot |
| `lineText` | `String` | le texte de cette ligne ; `""` si elle n'a pas pu être relue |

| Méthode | Rendu |
|---|---|
| `display()` | `<md5>:src/main/java/demo/Calc.java:7:13:add public int add(int a, int b) {` — la position seule si `lineText` est vide |
| `position()` | `<md5>:src/main/java/demo/Calc.java:7:13:add` — un `<position>` complet, renvoyable tel quel ; le préfixe md5 disparaît (sans `:` orphelin) s'il n'y en a pas |

Les cinq premiers champs **sont** un `<position>` : ce que clide imprime est
ce que clide accepte, sans rien à ajouter ni à recompter. Le `md5` signe le
fichier, pas la ligne : deux positions dans le même fichier portent le même. Ligne et colonne
viennent du début de la `range` renvoyée par jdtls, converties du 0-based LSP au
1-based client en un seul point (`JdtlsResponses.oneBased()`). Le nom est relu
depuis la **ligne source** à cette colonne (`JdtlsResponses.identifierAt()`),
pas pris chez jdtls : une `Location` LSP n'en porte aucun, et jdtls nomme un
type générique avec ses paramètres (`Box<T extends Comparable<T>>`) là où la
notation ne prend que le mot nu. Extraire le nom de la source rend donc la
sortie acceptable en entrée par construction.

Le séparateur entre la position et le texte de la ligne est une **espace**, pas
un `:` : un `:` tomberait là où la notation en utilise déjà, alors qu'un
découpage sur la première espace rend la position sans aucun parsing.

### `SymbolHit`

Un symbole trouvé par nom (`find_symbol`) ou listé comme membre d'un type
(`list_members`). Les deux commandes partagent cette forme parce que leurs
résultats *sont* de la même forme ; elles diffèrent par la façon dont
l'ensemble est choisi, pas par ce qu'est un élément.

| Champ | Type | Rôle |
|---|---|---|
| `kind` | `String` | `class`, `method`, `field`, `package`… (voir `JdtlsSession.symbolKindLabel()`) |
| `name` | `String` | le nom du symbole |
| `location` | `CodeLocation` | **peut être `null`** quand jdtls a rendu un symbole sans localisation ; l'entrée est conservée plutôt que jetée |

| Méthode | Rendu |
|---|---|
| `display()` | `[method] src/main/java/demo/Calc.java:7:13:add public int add(int a, int b) {` — ou `[method] add: <no location>` si `location` est `null` |

### `SearchMatch`

Une ligne trouvée par `search_regex`. Affichée `path:line: texte`,
délibérément **sans** la colonne ni le nom que porte un `CodeLocation` : une
correspondance de grep est une ligne, pas un symbole — il n'y a pas de début de
symbole à rapporter, et rien ici ne se recopie tel quel dans un `<position>`.
Type distinct de `CodeLocation` pour la même raison : les confondre inviterait à
traiter une correspondance textuelle comme une localisation sémantique.

| Champ | Type | Rôle |
|---|---|---|
| `path` | `String` | relatif au projet |
| `line` | `int` | 1-based |
| `text` | `String` | la ligne entière |

`display()` → `src/main/java/demo/Calc.java:4:  public int add(int a, int b) {`

### `Diagnostic`

Ce que le compilateur a dit d'une ligne d'un fichier.

| Champ | Type | Rôle |
|---|---|---|
| `path` | `String` | relatif au projet |
| `line` | `int` | 1-based ; `-1` si aucune plage exploitable |
| `severity` | `Diagnostic.Severity` | `ERROR`, `WARNING` ou `INFO` — les codes LSP 1/2/autre, nommés plutôt que laissés bruts |
| `message` | `String` | le message du compilateur |

`display()` → `[error] line 5: Type mismatch: cannot convert from String to int`
(le chemin est porté par l'en-tête de fichier, pas répété par entrée)

### `DiagnosticsReport`

L'ensemble des diagnostics du dernier build, plus les compteurs.

| Champ | Type | Rôle |
|---|---|---|
| `diagnostics` | `Listing<Diagnostic>` | filtrés (`errorsOnly`) puis plafonnés |
| `errorCount` | `int` | compté sur **tous** les diagnostics, avant filtrage et avant plafonnement |
| `warningCount` | `int` | idem |
| `fileCount` | `int` | nombre de fichiers porteurs de diagnostics |
| `errorsOnly` | `boolean` | si la liste a été réduite aux seules erreurs |
| `tracked` | `boolean` | `false` quand jdtls ne détient **rien** pour ce projet |
| `isClean()` | `boolean` | `errorCount == 0 && warningCount == 0` |

Deux points de méthode. Les compteurs décrivent **le projet**, pas l'extrait
affiché : les calculer après le filtre en ferait silencieusement une
statistique sur l'extrait. Et `tracked == false` signifie « rien n'a été
analysé », ce qui n'est pas « analysé et trouvé propre » — deux affirmations à
ne pas confondre, d'où `DiagnosticsReport.untracked()`.

### `TestOutcome`

Ce qui est arrivé à un test.

| Champ | Type | Rôle |
|---|---|---|
| `status` | `TestOutcome.Status` | `PASSED`, `FAILED` ou `SKIPPED` |
| `name` | `String` | `demo.CalcTest.addWorks`, ou `demo.UrlTest.parse [7] http://x` pour un cas de `@ParameterizedTest` |
| `location` | `String` | `src/test/java/demo/CalcTest.java:22` quand jdtls a su placer le test, `""` sinon |
| `messageLines` | `List<String>` | le message d'échec découpé en lignes (vide pour un succès) ; une liste plutôt qu'une chaîne, pour qu'un handler puisse l'indenter sans la redécouper |
| `origin` | `String` | d'où l'exception est réellement venue, quand ce n'est pas la ligne du test ; `""` sinon — cas normal d'une assertion échouée |

### `CommandSummary`

Une ligne de `help`, lue sur les annotations de la commande elle-même.

| Champ | Type | Rôle |
|---|---|---|
| `keyword` | `String` | le mot-clé |
| `parameters` | `List<String>` | les libellés des paramètres, dans l'ordre |
| `help` | `String` | la description d'une ligne |
| `parametersDisplay()` | `String` | `<what> <position>` |

---

## Les payloads

### `Nothing`

Aucun champ. Produit par `exit`, `quit`, `terminate`.

```
(aucune sortie)
```

`CommandPayload.NOTHING` est l'instance unique à réutiliser.

### `Text`

| Champ | Type | Rôle |
|---|---|---|
| `text` | `String` | passé tel quel, sans interprétation |

Produit par `man` (la page) et `hover` (le markdown de jdtls). Volontairement
non analysé : le pied de page `Source:` et le markdown sont l'affaire de
jdtls, et les reformater ici ne ferait que trouver de nouvelles façons de les
abîmer.

```
int demo.Calc.add(int a, int b)

Source: *[demo](file:///tmp/demo/src/main/java/demo/Calc.java#7)*
```

### `Locations`

| Champ | Type | Rôle |
|---|---|---|
| `subject` | `String` | le `<name>` sur lequel portait la question |
| `locations` | `Listing<CodeLocation>` | les résultats |

Produit par `find_declaration`, `find_reference`, `find_implementation`,
`find_callers`, `find_callees`, `find_supertypes`, `find_subtypes` - les
quatre derniers réutilisent cette même forme même si leur propre requête
jdtls (call/type hierarchy) n'est pas un simple `textDocument/xxx` comme les
trois premiers (voir `JdtlsSession.callHierarchyLocations()`/
`typeHierarchyLocations()`).

**Exemple** — `subject = "add"`, `totalCount = 3`, `maxResults = 100` :

```
find_reference: 3 location(s)
src/main/java/demo/Calc.java:12:10:add return add(add(a, 1), add(a, 2));
src/main/java/demo/Calc.java:12:14:add return add(add(a, 1), add(a, 2));
src/main/java/demo/Calc.java:12:25:add return add(add(a, 1), add(a, 2));
```

Liste vide (`totalCount == 0`) — un succès, pas une erreur :

```
find_reference: no location found
```

### `Symbols`

| Champ | Type | Rôle |
|---|---|---|
| `subject` | `String` | le nom cherché (`find_symbol`) ou le type inspecté (`list_members`) |
| `symbols` | `Listing<SymbolHit>` | les résultats |

Produit par `find_symbol` et `list_members`.

**Exemple tronqué** — `totalCount = 4`, `maxResults = 3` :

```
list_members: 3 member(s) shown out of 4, truncated - raise the limit with set_max_results
[field] src/main/java/demo/Calc.java:5:14:total private int total;
[method] src/main/java/demo/Calc.java:7:13:add public int add(int a, int b) {
[method] src/main/java/demo/Calc.java:11:13:chain public int chain(int a) {
```

Vide, selon la commande — chacune nomme la limite connue qui peut expliquer
un vide sans rapport avec le projet :

```
find_symbol: no symbol found for "total" - note that find_symbol matches types and methods only, never a field name
list_members: Calc has no direct members (inherited ones are never listed - see man list_members)
```

### `SearchMatches`

| Champ | Type | Rôle |
|---|---|---|
| `matches` | `Listing<SearchMatch>` | les lignes trouvées |
| `fileCount` | `int` | nombre de fichiers porteurs d'au moins une correspondance |

Produit par `search_regex`. Le décompte vient **en dernier** : c'est la ligne
qu'on veut après avoir fait défiler les correspondances, et c'est là que la
mention de troncature a sa place.

```
src/main/java/demo/Calc.java:4:  public int add(int a, int b) {
src/main/java/demo/Calc.java:12:   return add(add(a, 1), add(a, 2));
search_regex: 2 match(es) in 1 file(s)
```

### `Diagnostics`

| Champ | Type | Rôle |
|---|---|---|
| `report` | `DiagnosticsReport` | voir plus haut |

Produit par `print_diagnostics`.

```
src/main/java/demo/Broken.java:
  [error] line 5: Type mismatch: cannot convert from String to int
jdtls: 1 error(s), 0 warning(s) in 1 file(s)
```

Suivi par jdtls et propre (`tracked == true`, `isClean() == true`) - la tally
le dit en toutes lettres plutôt que d'imprimer des zéros :

```
jdtls: 12 file(s) with tracked diagnostics, no errors or warnings
```

Rien de suivi par jdtls (`tracked == false`) :

```
jdtls: no diagnostics (project not recognized, or nothing to report)
```

Ces deux cas ne se confondent pas : `tracked == false` veut dire que rien n'a
été analysé, `isClean() == true` que ça a été analysé et trouvé propre - voir
`DiagnosticsReport` plus haut.

Si la liste a été plafonnée, la troncature s'écrit sur **sa propre ligne** :
les deux nombres comptent des choses différentes, et les fondre inviterait à
lire l'un pour l'autre.

```
jdtls: 312 error(s), 8 warning(s) in 47 file(s)
jdtls: 100 diagnostic(s) shown out of 320, truncated - raise the limit with set_max_results
```

### `Rebuild`

| Champ | Type | Rôle |
|---|---|---|
| `changedFiles` | `int` | fichiers signalés modifiés depuis le dernier build |
| `elapsedMillis` | `long` | durée du rafraîchissement + build |
| `report` | `DiagnosticsReport` | le même que `print_diagnostics` |

Produit par `rebuild`. La tally qui termine `report` suit les mêmes trois
formes que `print_diagnostics` (propre, en erreur, jamais suivi) - voir
`Diagnostics` ci-dessus.

```
rebuild: 2 file(s) changed since the last build, rebuilt in 9124 ms
src/main/java/demo/Broken.java:
  [error] line 5: Type mismatch: cannot convert from String to int
jdtls: 1 error(s), 0 warning(s) in 1 file(s)
```

Un build qui échoue lui-même (par opposition à un build qui réussit en
rapportant des erreurs de compilation) donne `?ERROR BUILD_FAILED`, et laisse
intacts les diagnostics du build précédent.

### `TestRun`

| Champ | Type | Rôle |
|---|---|---|
| `subject` | `String` | ce qui a été sélectionné (classe, méthode, dossiers scannés) |
| `passed` | `int` | comptés sur **tout** le run |
| `failed` | `int` | idem |
| `skipped` | `int` | idem |
| `elapsedMillis` | `long` | durée |
| `tests` | `Listing<TestOutcome>` | les entrées, filtrées par `failuresOnly` puis plafonnées |
| `failuresOnly` | `boolean` | si la liste a été réduite aux échecs |
| `total()` | `int` | `passed + failed + skipped` |

Produit par `run_test` et `run_tests`. Les compteurs portent sur le run entier,
si bien que « 12 test(s), 9 passed » reste vrai même quand seuls les 3 échecs
sont listés.

Succès :

```
run_test: 3 test(s), 3 passed, 0 failed in 412 ms
[passed] demo.CalcTest.addWorks
[passed] demo.CalcTest.chainWorks
[passed] demo.CalcTest.totalStartsAtZero
```

Échec — **le seul cas où un `ERROR` porte un payload** : le statut reste
`ERROR` parce que « est-ce que mes tests passent » est la question posée, et
qu'un client qui ne regarde que le statut ne doit pas lire une suite rouge
comme verte. La liste voyage tout de même, sous l'en-tête au lieu d'être
perdue avec lui.

```
?ERROR TEST_FAILURES: 1 test(s) failed out of 3
run_tests: 3 test(s), 2 passed, 1 failed in 388 ms
[failed] src/test/java/demo/CalcTest.java:22: demo.CalcTest.addWorks
    expected: <5> but was: <4>
    thrown at src/main/java/demo/Calc.java:9
```

Le nom est qualifié complet sur un échec comme sur un succès. Avant le passage
aux payloads, un échec n'affichait que le nom de méthode nu (`addWorks`) là où
un succès affichait le nom qualifié — `TestOutcome.name` porte désormais une
identité de test unique, et les deux lignes se lisent pareil. Redondant avec la
localisation qui précède, mais lisible seul, ce qu'une ligne extraite d'une
suite de plusieurs centaines finit toujours par être.

### `Transaction`

| Champ | Type | Rôle |
|---|---|---|
| `id` | `String` | l'identifiant, non vide |
| `action` | `Transaction.Action` | `OPENED`, `COMMITTED`, `ROLLED_BACK`, `FILE_RESTORED` |
| `path` | `String` | rempli seulement pour `FILE_RESTORED`, `""` sinon |

Produit par `open_transaction`, `commit_transaction`, `rollback_transaction`,
`restore_file`.

```
Transaction $refactor_foo opened.
Transaction $refactor_foo committed.
Transaction $refactor_foo rolled back.
Restored src/main/java/demo/Calc.java to its state before transaction $refactor_foo.
```

### `ModifiedFiles`

| Champ | Type | Rôle |
|---|---|---|
| `transactionId` | `String` | la transaction concernée |
| `files` | `Listing<String>` | chemins relatifs |

Produit par `list_modified_files`.

```
list_modified_files: 2 file(s)
src/main/java/demo/Calc.java
src/main/java/demo/Broken.java
```

```
Transaction $refactor_foo has not modified any file yet.
```

### `Diff`

| Champ | Type | Rôle |
|---|---|---|
| `transactionId` | `String` | la transaction concernée |
| `path` | `String` | le fichier |
| `unifiedDiff` | `String` | format `diff -u` ; **vide** si le contenu actuel correspond à la sauvegarde |

Produit par `diff_transaction`.

```
--- a/src/main/java/demo/Calc.java
+++ b/src/main/java/demo/Calc.java
@@ -3,5 +3,5 @@
 public class Calc {
-	public int add(int a, int b) {
+	public int add(final int a, final int b) {
 		return a + b;
```

Un diff vide est un fait à rapporter, pas une absence :

```
No differences (current content matches the pre-transaction backup).
```

### `Rename`

| Champ | Type | Rôle |
|---|---|---|
| `subject` | `String` | le nom d'avant |
| `newName` | `String` | le nom d'après |
| `changedFiles` | `Listing<String>` | fichiers touchés — pas un compte d'occurrences, voir plus bas |
| `fileRenames` | `List<Rename.FileRenaming>` | un type public renommé renomme aussi son fichier — à part, jamais mêlé à `changedFiles` |
| `declaration` | `CodeLocation` | la position fraîche du symbole renommé, `null` si clide n'a pas pu en dériver une vérifiée |
| `errorCount` | `int` | ce que le build qui suit l'edit a rapporté |

Produit par `rename`. Pas de compte d'occurrences : jdtls fusionne deux
occurrences voisines en un seul edit, donc un compte dérivé des edits
ressemblerait à un compte d'occurrences sans en être un — `find_reference`
sur `declaration` donne le vrai compte.

```
rename: Square -> Rectangle, 2 file(s)
src/demo/Main.java
src/demo/Rectangle.java
file renamed: src/demo/Square.java -> src/demo/Rectangle.java
declaration now at f21e4159:src/demo/Rectangle.java:3:14:Rectangle public class Rectangle {
rebuilt: 0 error(s)
```

Rien à changer se dit, plutôt que d'annoncer 0 fichier :

```
rename: nothing to change - 'Square' is already called 'Square'
```

### `RemoveUnusedImports`

| Champ | Type | Rôle |
|---|---|---|
| `matchedFileCount` | `int` | fichiers dont le chemin matche `<path regex>`, changés ou non |
| `changedFiles` | `Listing<RemoveUnusedImports.FileChange>` | seulement les fichiers réellement réécrits |
| `errorCount` | `int` | ce que le build qui suit l'edit a rapporté |

`FileChange` porte `path` et `removedImports` (`List<String>`, dans l'ordre
d'apparition dans le fichier). Produit par `remove_unused_imports`. Un
fichier matché mais déjà propre n'apparaît pas dans `changedFiles` — ce n'est
pas une erreur, `matchedFileCount` le dit déjà.

```
remove_unused_imports: 3 file(s) matched, 1 file(s) changed
src/demo/Calc.java: removed java.util.List, java.util.ArrayList
rebuilt: 0 error(s)
```

```
remove_unused_imports: 2 file(s) matched, nothing to remove
```

### `CommandList`

| Champ | Type | Rôle |
|---|---|---|
| `commands` | `Listing<CommandSummary>` | **jamais plafonné** — voir ci-dessous |

Produit par `help`. Le plafond de session ne s'applique pas : un `help` qui ne
listerait qu'une partie des commandes serait une liste sur laquelle un client
ne peut pas raisonner, et leur nombre est borné par construction.

C'est **le seul payload dont le rendu dépend de `PrintMode`** — et la raison
pour laquelle `Command.render()` en reçoit un. Les deux formes disent
exactement la même chose, à partir du même payload.

Mode AI (défaut) :

```
find_reference <What: method or type> <Position> - Finds every real usage of a symbol across the whole project - ...
help - Lists every available command with its parameters - one line each, or an ASCII table under --human.
```

Mode HUMAN (daemon démarré avec `java -jar clide.jar --human`) : le même
contenu en table ASCII à largeur fixe.

### `Setting`

| Champ | Type | Rôle |
|---|---|---|
| `name` | `String` | le réglage (`max_results` aujourd'hui) |
| `previousValue` | `String` | la valeur d'avant |
| `newValue` | `String` | la valeur d'après |

Produit par `set_max_results`. Porter la valeur précédente est ce qui fait de
la commande sa propre relecture : l'arité fixe du protocole ne laisse aucune
place à une forme sans argument « montre-moi la valeur courante ».

```
set_max_results: max_results 100 -> 3
```

---

## Récapitulatif : quelle commande rend quel payload

| Commande | Payload | Plafonné par `max_results` |
|---|---|---|
| `find_declaration` | `Locations` | oui |
| `find_reference` | `Locations` | oui |
| `find_implementation` | `Locations` | oui |
| `find_callers` | `Locations` | oui |
| `find_callees` | `Locations` | oui |
| `find_supertypes` | `Locations` | oui |
| `find_subtypes` | `Locations` | oui |
| `find_symbol` | `Symbols` | oui |
| `list_members` | `Symbols` | oui |
| `search_regex` | `SearchMatches` | oui |
| `print_diagnostics` | `Diagnostics` | oui |
| `rebuild` | `Rebuild` | oui |
| `run_test` | `TestRun` | oui |
| `run_tests` | `TestRun` | oui |
| `list_modified_files` | `ModifiedFiles` | oui |
| `diff_transaction` | `Diff` | non |
| `hover` | `Text` | non |
| `man` | `Text` | non |
| `help` | `CommandList` | non (délibérément) |
| `set_max_results` | `Setting` | non |
| `open_transaction` | `Transaction` | non |
| `commit_transaction` | `Transaction` | non |
| `rollback_transaction` | `Transaction` | non |
| `restore_file` | `Transaction` | non |
| `rename` | `Rename` | oui (`changedFiles`) |
| `remove_unused_imports` | `RemoveUnusedImports` | oui (`changedFiles`) |
| `exit` / `quit` | `Nothing` | — |
| `terminate` | `Nothing` | — |

---

## Ajouter une commande : ce qu'il y a à écrire

1. Choisir un payload **existant** si la forme du résultat en est déjà une. Un
   payload par forme, pas par commande.
2. Sinon, ajouter un record dans `CommandPayload`. Il est scellé : chaque
   `switch` qui ne le prend pas en compte cesse de compiler — c'est ainsi
   qu'un nouveau payload obtient un rendu au lieu de n'en avoir aucun.
3. Le payload reste **sans présentation** : pas de `(s)`, pas d'alignement, pas
   de retour à la ligne. Deux rendus dans le code, c'est deux rendus qui
   divergeront.
4. Surcharger `Command.render()`. Il rend **le corps seulement** — l'en-tête
   d'erreur, le hint et les avertissements sont ajoutés autour par
   `ResultEnvelope`. Rendre `""` est normal.
5. Si la réponse est une liste : `Listing.of(tout, context.getMaxResults())`,
   avec la liste complète, jamais une liste déjà écourtée.
