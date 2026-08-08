# LUA.md — Scripter clide en Lua

Ce que le pont Lua doit devenir. `CLAUDE.md` décrit ce qui marche
aujourd'hui — `clide --lua`, les fonctions bindées, ce qu'un script voit ;
ce document ne garde que ce qui reste à construire par-dessus, les conventions
encore à figer et les pièges à ne pas redécouvrir en route.

## Objectif

clide se pilote commande par commande : Claude tape une commande, lit la
réponse, décide de la suivante. Ce mode reste le mode par défaut et ne
disparaît pas.

On y ajoute un second mode : des scripts **Lua**, capables d'enchaîner
plusieurs commandes clide sans repasser par un tour de décision de Claude entre
chacune — utile pour des refactors mécaniques et bien définis, et pour les
questions dont la réponse demande une boucle, là où la logique (filtrer,
compter, décider commit/rollback) est plus naturelle à écrire d'un coup qu'à
piloter pas à pas.

**Les deux modes coexistent, aucun ne remplace l'autre.** Claude choisit,
commande par commande ou script Lua, selon la tâche. Ça oblige à concevoir toute
évolution du cœur de clide pour servir les deux façades à la fois, sans
dupliquer la logique métier ni laisser l'une contourner les garde-fous de
l'autre.

## Premier exemple : lecture seule, sur les commandes d'aujourd'hui

Cet exemple n'appelle **que des commandes enregistrées dans
`CommandRepository`** — ni transaction, ni écriture. Il tourne
(`clide --lua phase1.lua <projet>`), et sert de test d'acceptation du pont :
c'est lui qui dit si les fonctions bindées, la conversion des payloads et la
validation des positions tiennent ensemble.

Il répond à une question qu'aucune commande unique ne répond, et qui demande une
boucle : *parmi les méthodes de ce type, lesquelles ne sont appelées nulle
part ?* Un `list_members`, puis un `find_reference` par membre, et un compte —
trois tours de protocole texte par méthode en mode conversationnel, un seul
appel de script ici.

```lua
-- Audit lecture seule : les méthodes de ce type sans aucun appelant.
local TYPE = "src/main/java/clide/core/TransactionStack.java:35:20:TransactionStack"

-- Le plafond de troncature est hérité de la connexion (100 par défaut) et
-- s'applique aussi aux résultats vus depuis Lua. On le remonte, et
-- set_max_results renvoie l'ancienne valeur, seule façon de la relire.
local cap = set_max_results(1000)
print(string.format("max_results : %s -> %s", cap.previousValue, cap.newValue))

-- clide ne voit pas les fichiers édités en dehors de lui : un audit qui lit
-- l'index sans l'avoir rafraîchi peut décrire un état périmé.
local built = rebuild("errors")
if built.report.errorCount > 0 then
  print(string.format("%d erreur(s) de compilation - audit abandonné",
      built.report.errorCount))
  return
end

local members = list_members(TYPE)
local unused, checked, unlocated = {}, 0, 0

for _, member in ipairs(members.symbols.items) do
  if member.kind == "method" then
    if member.location == nil then
      -- jdtls renvoie parfois un symbole sans position : on le compte
      -- comme non examiné plutôt que comme non appelé.
      unlocated = unlocated + 1
    else
      local refs = find_reference("method", member.location.position)
      checked = checked + 1
      if refs.locations.totalCount == 0 then
        table.insert(unused, member)
      end
    end
  end
end

print(string.format("%s : %d méthode(s) examinée(s), %d sans appelant",
    members.subject, checked, #unused))
for _, member in ipairs(unused) do
  local at = member.location.position
  print(string.format("  %s:%d:%d:%s", at.path, at.line, at.column, at.name))
end
if unlocated > 0 then
  print(string.format("(%d méthode(s) non examinée(s) : aucune position)", unlocated))
end
```

Ce que cet exemple engage, et qu'il faut donc trancher pour lui :

- **La forme des retours.** `list_members` et `find_symbol` répondent tous deux
  un `CommandPayload.Symbols(subject, Listing<SymbolHit>)`, d'où
  `members.symbols.items` et `members.subject` ; `find_reference` répond un
  `Locations(subject, Listing<CodeLocation>)`, d'où `refs.locations.totalCount`.
  Le script lit `totalCount`, pas `#items` : c'est la seule lecture juste quand
  la liste peut être tronquée.
- **Une `Position` circule comme table.** `member.location.position` est passée
  telle quelle à `find_reference`, sans jamais repasser par la chaîne
  `md5:chemin:ligne:colonne:nom`. C'est exactement le `PositionParser.of(...)`
  réclamé plus bas — et le raccourci n'a plus besoin d'être sûr par hasard : la
  table porte le `md5` du fichier, donc si quelque chose écrit entre le
  `list_members` et les `find_reference`, l'appel lève `FILE_MODIFIED` au lieu
  de répondre sur un fichier qui a bougé.
- **`location` peut être `nil`.** `SymbolHit.location` est nullable (jdtls
  renvoie parfois un symbole sans emplacement) et `display()` le gère déjà côté
  texte. Un script qui l'ignore compterait ces méthodes comme « sans appelant »,
  c'est-à-dire produirait une réponse fausse plutôt qu'une erreur.
- **Aucune gestion d'erreur.** Le script suppose qu'un `status == ERROR` lève une
  erreur Lua, attrapable par `pcall`, plutôt que de renvoyer une valeur à
  tester. C'est une des conventions non tranchées (voir « Conversion ») : si le
  choix inverse est fait, chaque appel ici gagne un test.
- **`rebuild` n'est pas gratuit** : 9 à 12 s sur un projet de la taille de
  PlantUML, même sans changement. Le mettre en tête d'un script d'audit est
  correct ; le mettre dans une boucle ne le serait pas.
- **`kind`** est l'étiquette que produit `JdtlsSession.symbolKindLabel()`
  (`"class"`, `"method"`, `"field"`…). Que la table Lua reçoive cette chaîne
  telle quelle fait partie des conventions à figer.

## Second exemple : écriture, à terme

Celui-ci appelle `replace_symbol`, qui n'existe pas, et les commandes de
transaction, qui ne sont pas enregistrées. Il sert de cas de référence pour le
design d'ensemble, et de test d'acceptation de la seconde phase :

```lua
-- Renommer un ancien nom de méthode, mais seulement dans les fichiers de test
local refs = find_reference("method",
    "src/main/java/net/sourceforge/plantuml/Foo.java:42:17:legacyCompute")
open_transaction("$rename_legacy_compute")
local touched = 0
for _, ref in ipairs(refs.locations.items) do
  if ref.position.path:match("Test%.java$") then
    replace_symbol(ref.position, "computeLegacy")
    touched = touched + 1
  end
end
if touched > 0 then
  print(string.format("%d fichiers de test modifiés", touched))
  commit_transaction("$rename_legacy_compute")
else
  rollback_transaction("$rename_legacy_compute")
  print("Aucun appelant trouvé dans les tests, rien à faire")
end
```

La commande d'édition reçoit une `Position` déjà résolue plutôt qu'un triplet
fichier/ligne/nom re-matché par nom : deux homonymes sur une même ligne sont
improbables mais possibles, et la colonne de la notation `<position>` est là
pour ça.

## Ce sur quoi le pont s'appuie

**Le résultat structuré existe, de bout en bout.** `CommandResult`
(`clide.command.answer`) est un record à six champs — `status`, `code`
(`ErrorCode`, `NONE` exactement quand `status == OK`), `message`, `hint`,
`warnings`, `payload` — et le `payload` n'est jamais `null`
(`CommandPayload.NOTHING` quand il n'y a rien à dire). Le texte n'est pas le
résultat : il est produit par `Command.render()` à partir du payload, et
l'enveloppe commune (`?ERROR <CODE>:`, `hint:`, `!WARNING`) est écrite une fois
pour toutes dans `ResultEnvelope`. C'est la séparation dont le pont a besoin :
Lua lit le payload, sans jamais reparser le texte.

**La forme du payload est une interface scellée, `CommandPayload`.**
`Monomorphic` modélise « une valeur dont personne n'a promis la forme », ce qui
est juste à la frontière jdtls, où la forme vient réellement du dehors, et faux
ici, où le producteur du payload et son lecteur sont dans le même dépôt et
peuvent donc être mis d'accord par le compilateur. Treize records imbriqués :
`Nothing`, `Text`, `Locations`, `Symbols`, `SearchMatches`, `Diagnostics`,
`Rebuild`, `TestRun`, `Transaction`, `ModifiedFiles`, `Diff`, `CommandList`,
`Setting` — **un payload par *forme* de résultat, pas par commande**
(`find_declaration`/`find_reference`/`find_implementation` partagent
`Locations`).

**Le comptage et la troncature vivent dans `Listing`.** `Listing<T>(items,
totalCount, maxResults)` (`clide.model`) porte le compte réel, le plafond et
`truncated()` dérivé du premier — délibérément *sous* `CommandResult` plutôt que
dans l'enveloppe, pour que `hover` et `open_transaction` n'aient pas à répondre
à une question qui ne les concerne pas.

**La structure n'est écrasée nulle part avant `CommandResult`.**
`JdtlsSession.goToPosition()`/`findMethodImplementations()` renvoient des
`List<CodeLocation>`, et `PositionCommandSupport` est scindé en `goTo()` (qui
produit la donnée) et `render()` (qui produit le texte).

**Les transactions sont écrites, migrées, mais désenregistrées.**
`TransactionStack`/`Transaction` (`clide.core`) fournissent la pile LIFO de
sous-transactions, la politique « premier backup gagne » et le
`refuseIfDirty()` au démarrage du daemon. Les six commandes (`open_transaction`,
`commit_transaction`, `rollback_transaction`, `list_modified_files`,
`diff_transaction`, `restore_file`) existent, compilent, et ont leurs payloads
(`Transaction`, `ModifiedFiles`, `Diff`). Mais leurs six `registered.add(...)`
sont commentés dans `CommandRepository` : elles ne sont atteignables ni depuis
le protocole texte ni, a fortiori, depuis Lua. C'est pourquoi le second exemple
ne tourne pas — `open_transaction`, sa première ligne, n'a aucune commande
derrière elle.

**Les commandes de lecture** (`find_reference`/`find_declaration`/
`find_implementation`/`find_symbol`/`hover`/`list_members`/`search_regex`, plus
`rebuild`/`print_diagnostics`/`run_test`/`run_tests`/`set_max_results`/`help`/
`man`) sont enregistrées et testées de bout en bout ; `exit`/`quit`/`terminate`
sont les trois seules à déclarer `isScriptable()` false.

**Le pattern Command et sa réflexion** (`clide.core.Command`,
`CommandRepository`, annotations `@Keyword`/`@Param`/`@Help`/`@Manual` lues sur
le constructeur sans-argument) génèrent `help`/`man` sans code par commande.
`getParamTypes()` donne l'arité et le `ParamType` de chaque paramètre
(`TRANSACTION_ID`, `REGEX`, `POSITION`, `NON_NEGATIVE_INTEGER`, `SINGLE_LINE`,
`MULTI_LINE`) — c'est de là que vient la signature des fonctions Lua.

**La staleness de l'index est détectée.** `FilesRepository` scanne les sources
en parallèle, `Md5Repository` signe leur contenu, `Snapshot` fige un instant et
`compareWithPreviousSnapshot()` produit un `Delta` de `FileChange`, que
`JdtlsSession.refreshChangedFiles()` traduit en
`workspace/didChangeWatchedFiles`. Ce qui est comparé est le **contenu**, pas le
mtime : un fichier réécrit à l'identique n'est pas un changement, un fichier
édité deux fois dans la même seconde en est un.

**luajava est vendoré.** `lib/` contient `luajava-4.1.0.jar`,
`lua51-4.1.0.jar`, `lua51-platform-4.1.0-natives-desktop.jar`,
`jnigen-loader`/`jnigen-commons` (chargement de la bibliothèque native) et
`jspecify` ; `build.xml` et `build.gradle.kts` les câblent,
`scripts/fetch_luajava.py` explique d'où ils viennent. Le backend est le Lua
5.1 natif (`Lua51`), et il tourne dans le process du daemon : c'est la seule
place où les commandes ont une `JdtlsSession` et un projet à interroger.

## Là où le pont en est

Un script tourne : `clide --lua <script> <projet>` envoie le fichier au daemon,
qui le lit jusqu'à EOF et l'exécute avec chaque commande bindée comme fonction
de même nom (`clide.lua.LuaBridge`, `ConnectionMode.SCRIPT`) — voir
« Scripting a session » dans `CLAUDE.md` pour ce qu'un script voit. Les
garde-fous sont dans `CommandDispatcher`, appelé aussi bien par le protocole
texte que par le pont, donc un script ne peut pas les contourner. Le premier
exemple ci-dessus tourne de bout en bout.

Ce qui reste, et que ce document couvre : la phase 2 — les transactions,
`replace_symbol`, et les questions que seul un script qui écrit oblige à
trancher.

Deux propriétés du daemon dont un script hérite, à garder en tête plutôt qu'à
découvrir : il sert ses clients **un à la fois** (boucle `accept()`
séquentielle), donc un script long bloque toute autre connexion pendant sa
durée ; et `maxResults` est un réglage **par connexion**, donc un
`find_reference` appelé depuis Lua est tronqué à 100 par défaut comme
n'importe quel autre.

## Conversion `CommandPayload` → table Lua

Le pont a besoin d'un convertisseur récursif payload → valeur Lua, **miroir de
`render()`** : là où `render()` produit du texte, il produit une table. Un
`switch` exhaustif sur les treize `CommandPayload`, plus la poignée de records
de `clide.model` qu'ils contiennent — `Listing<T>`, `Position(md5, path, line,
column, name)`, `CodeLocation(position, lineText)`, `SymbolHit(kind, name,
location)`, `SearchMatch(path, line, text)`, `TestOutcome(status, name,
location, messageLines, origin)`, `Diagnostic(path, line, severity, message)`,
`DiagnosticsReport`, `CommandSummary`.

Ce que le choix scellé coûte : une nouvelle commande n'obtient pas sa fonction
Lua tout à fait gratuitement, puisqu'un nouveau payload demande aussi son `case`
dans le convertisseur. Ce qu'il rapporte : **ce `case` manquant ne compile
pas** — la propriété qui manquerait à une `Map<String,Object>`, où un nom de clé
fautif ne se verrait qu'en production. Le contrat est donc : *les arguments*
d'une fonction Lua se génèrent par réflexion, *le retour* se déclare une fois
par forme de payload.

**Les conventions de forme sont figées** dans `LuaPayloads` et verrouillées par
`LuaPayloadsTest` : un nom de clé est ce qu'un script écrit en dur, donc le
changer casse silencieusement tous les scripts déjà écrits, et ces tests sont
le seul endroit où ce changement devient visible. Une `Listing` est
`{items, totalCount, maxResults, truncated}` et non un tableau nu — un script
qui compte `#items` compte faux dès que la réponse est tronquée. Un `enum` Java
arrive en minuscules (`"error"`, `"rolled_back"`), comme les `kind` que jdtls
donne déjà sous cette forme. Une chaîne vide reste `""` : la clé qui disparaît
d'une table Lua ne se distingue plus de celle que personne n'a écrite. Un
`null` Java devient `nil`, le seul d'aujourd'hui étant `SymbolHit.location`.
Une commande refusée lève, plutôt que de rendre une valeur à tester : c'est la
convention Lua, et le bon défaut pour un script qui écrira un jour — une
défaillance non testée arrête le script au lieu de le laisser continuer en
croyant l'édition faite.

Reste à trancher, quand une commande le demandera : ce que devient la valeur de
retour du script lui-même (aujourd'hui rien ne la remonte), et si un script
doit pouvoir lire les `warnings` d'un résultat autrement que par la ligne
`!WARNING` qui part sur la sortie.

## La commande de modification

**Aucune commande ne modifie un fichier**, et c'est le prérequis de la seconde
phase.

Le besoin du second exemple (« renommer seulement dans les fichiers de test »)
n'est pas un rename sémantique au sens LSP : `textDocument/rename` renvoie un
`WorkspaceEdit` couvrant *toutes* les occurrences, sans notion de sous-ensemble.
Ce qu'il faut est plus bas niveau : `replace_symbol`, opérant sur **une seule
`Position` déjà résolue** à la fois, en substitution textuelle locale, sans
appel LSP pour l'écriture elle-même. Tout le nécessaire existe :
`PositionParser.parse()` valide la position, `Position.fileIn(projectRoot)`
donne le fichier réel, `TransactionStack.backupBeforeModification()` sauvegarde
avant écriture. Il ne manque que la substitution et l'écriture.

Rappel de `JAVALENSE.md` (idée n°6) : proposer un diff avant d'écrire, jamais
d'écriture directe. C'est ce que le couple `diff_transaction`/
`commit_transaction` offre — à condition de les réenregistrer.

## `Position` : le raccourci et ce qu'il ne doit pas faire sauter

Un script a une table `{path, line, column, name}` sous la main — celle que le
convertisseur vient de lui donner — là où `PositionParser.parse()` attend le
token unique `<chemin>:<ligne>:<colonne>:<nom>` d'un client texte recopiant un
résultat précédent. Le pont accepte les deux, la table passant par
`PositionParser.of()`.

Ce qui compte est que ce raccourci ne saute pas la validation. Le constructeur
canonique de `Position` ne vérifie qu'une chose : que le chemin est relatif au
projet. Toute la validation utile — le nom commence bien à cette colonne, comme
mot entier, la ligne existe, le fichier est lisible — vit dans `parse()`, et la
javadoc de `Position` signale le manque comme assumé (« PENDING ») : une
`Position` transportée et réutilisée en mémoire contournerait entièrement cette
vérification et pourrait être devenue fausse. D'où `of()` qui épelle le token et
appelle `parse()` plutôt que de refaire les contrôles à côté : deux copies des
mêmes vérifications sont exactement la façon dont les deux chemins finiraient
par diverger.

Le premier exemple fait circuler une `Position` sans rien écrire entre les deux
appels, donc rien ne peut avoir bougé. Le second écrit au milieu — c'est là que
la revalidation cesse d'être une formalité.

## Staleness de l'index : une politique à choisir

La mécanique de détection existe (`Snapshot`/`Delta`/`refreshChangedFiles()`).
Ce qui reste est une question de politique, et elle se pose plus fort en mode
script qu'en conversationnel : un script qui enchaîne modification → requête →
modification sans jamais rendre la main est exactement le scénario où un index
périmé ferait le plus de dégâts, sans personne pour trouver la réponse suspecte.

Question non tranchée : au sein d'une même transaction Lua, un `find_reference`
appelé après un `replace_symbol` doit-il voir l'état déjà modifié (index
« live », donc un `refreshChangedFiles()` implicite après chaque écriture, avec
son coût — la pause d'une seconde qu'il s'impose, et le `rebuild` complet à
9–12 s sur un projet de la taille de PlantUML) ou rester figé sur l'état d'avant
(snapshot) ? Les deux ont un sens selon le script ; le choix doit être
documenté, pas laissé implicite.

## Round-trips : commandes composées vs composition en Lua

`JAVALENSE.md` (idée n°3) motive ses commandes composées (`analyze_type`,
`analyze_method`…) par la réduction des allers-retours d'agent. Cette motivation
s'affaiblit une fois qu'un script Lua existe : à l'intérieur d'un script, un
appel de fonction est quasi gratuit et la composition se fait déjà là. Partage
des rôles envisagé : garder les commandes composées côté texte pour le mode tour
par tour, garder les primitives Lua fines, et laisser la composition aux scripts
plutôt que de la dupliquer côté serveur.

## Questions ouvertes

- **Gestion d'erreur d'un script qui écrit** : tout le script enveloppé dans un
  `pcall` par clide, avec rollback automatique de toute transaction encore
  ouverte sur exception non rattrapée ? Ou à la charge du script, comme dans le
  second exemple ? Aujourd'hui une erreur non rattrapée arrête le script et
  rien d'autre - ce qui suffit tant que rien n'écrit.
- **Un `--dry-run` global** qui force le rollback quel que soit le script,
  indépendamment de sa propre logique — utile en CI ou pour explorer sans
  risque ?
- **`find_reference` après modification dans la même transaction** : index live
  ou snapshot (voir « Staleness »).
- **Transaction implicite** : une transaction ouverte/fermée automatiquement
  autour du script, ou explicite comme dans le second exemple ?
- **Garde-fous applicatifs** : seuil de fichiers touchés avant confirmation,
  refus de `commit_transaction` si l'arbre git n'est pas propre ?
- **`maxResults` côté Lua** : un script hérite du plafond de sa connexion et
  peut donc lire une liste tronquée sans le savoir. Il peut appeler
  `set_max_results` lui-même ; reste à décider si c'est suffisant, ou si le
  pont doit faire mieux que compter sur la vigilance de l'auteur.
- **Bibliothèques Lua** : `base`, `string`, `table` et `math` sont ouvertes,
  `io`/`os`/`package`/`debug` non — un script tourne dans le process du daemon,
  et tout ce qu'il touche passe par une commande. À rouvrir si un besoin réel
  se présente, une bibliothèque à la fois plutôt que d'un `openLibraries()`.
- **Combinateurs de plus haut niveau** (`rename_symbol_where(predicate)`) dans
  le cœur de clide, ou en Lua pur dans une bibliothèque de scripts ?

## Pièges identifiés

- **Les commandes de transaction sont commentées dans `CommandRepository`** :
  elles compilent et ont leurs payloads, mais ne sont pas atteignables.
- **Troncature silencieuse** : `maxResults` (100 par défaut) s'applique aux
  résultats vus depuis Lua comme depuis le texte. Un script qui lit `items` sans
  regarder `totalCount` travaille sur un sous-ensemble sans le savoir.
- **`Position` réutilisée en mémoire** — *réglé.* Sa validation vit toujours
  dans `PositionParser.parse()` et non dans son constructeur, mais la table Lua
  porte désormais le `md5` du contenu du fichier, et `PositionParser.of()` le
  revérifie : une position transportée d'un appel Lua au suivant à travers une
  écriture est refusée (`FILE_MODIFIED`), au lieu de désigner autre chose en
  silence. Une table écrite à la main sans clé `md5` vaut, elle, « sur le
  fichier actuel » — c'est un choix explicite, pas un oubli du contrôle.
- **`SymbolHit.location` est nullable** : l'ignorer ne produit pas une erreur,
  mais une réponse fausse.
- **Rename LSP ≠ rename partiel** : `textDocument/rename` est tout-ou-rien sur
  l'ensemble des références ; le besoin du second exemple est une substitution
  localisée. Deux commandes différentes, pas une seule avec un filtre.
- **`print()` de Lua écrit sur la sortie native du process**, c'est-à-dire le
  log du daemon. Le pont le remplace ; toute autre fonction qui écrirait (un
  `io.write` si `io` était ouvert un jour) retomberait dans le même trou.
- **Id de transaction** : `$` obligatoire en préfixe, `\w` minuscule ensuite
  (`TransactionStack.ID_PATTERN`, `ParamType.TRANSACTION_ID`) — la validation
  est partagée, donc l'erreur côté Lua est le même
  `?ERROR INVALID_TRANSACTION_ID`.
- **Un script long bloque le daemon**, qui sert ses clients un à la fois.
- **Une commande, deux formes de réponse selon un argument** : un paramètre
  optionnel qui change la forme du payload casse l'hypothèse « chaque commande
  déclare une forme » sur laquelle repose la génération des fonctions Lua — même
  fonction, deux tables différentes selon l'argument. À vérifier pour toute
  commande dont l'arité admettrait un paramètre optionnel.
- **Un nouveau payload sans son `case`** dans `LuaPayloads` ne compile pas :
  c'est voulu, et c'est le seul rappel qu'une commande ajoutée doit aussi
  décider de ce qu'un script en voit.

## Prochaines étapes

Phase 1 tourne. Ce qui suit est la phase 2, l'écriture — chaque étape validant
la suivante, et le second exemple servant de test d'acceptation à l'ensemble.

1. **Réenregistrer les six commandes de transaction** dans `CommandRepository`
   (ou écrire pourquoi elles sont désactivées, si c'est délibéré). Elles ont
   déjà leurs payloads, donc leurs fonctions Lua viennent avec.
2. **Écrire `replace_symbol`**, la première vraie commande de modification, sur
   les primitives existantes (`Position`, `TransactionStack`) — et lui faire
   déclarer `needsOpenTransaction()`, que `CommandDispatcher` fait déjà
   respecter des deux côtés.
3. **Faire tourner le second exemple**, et trancher au passage ce que seul un
   script qui écrit oblige à trancher : `pcall` et rollback automatique,
   `--dry-run`, index live ou snapshot après écriture.
4. **Reprendre la staleness** : une écriture depuis un script rend l'index
   périmé pour le `find_*` suivant du même script, sans personne pour trouver
   la réponse suspecte.
