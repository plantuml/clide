# LUA.md — Scripter clide en Lua

Ce que le pont Lua doit devenir : ce qu'on veut en faire, ce sur quoi il
s'appuie dans le code, ce qui reste à écrire, et les pièges à ne pas
redécouvrir en route. Rien du pont lui-même n'est implémenté aujourd'hui —
seul un runtime nu tourne (voir « Ce sur quoi le pont s'appuie »).

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
`CommandRepository`** — ni transaction, ni écriture. C'est donc lui, et pas le
suivant, qui sert de cible au premier pont : le jour où le pont existe, ce
script doit tourner sans qu'aucune commande nouvelle ait été écrite.

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
  `chemin:ligne:colonne:nom`. C'est exactement le `PositionParser.of(...)`
  réclamé plus bas — et ici le raccourci est sans risque, puisque rien n'écrit
  entre le `list_members` et les `find_reference`.
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
`man`) sont enregistrées et testées de bout en bout. Ce sont les briques
directement exposables en Lua.

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

**luajava est vendoré et un runtime nu tourne.** `lib/` contient
`luajava-4.1.0.jar`, `lua51-4.1.0.jar`, `lua51-platform-4.1.0-natives-desktop.jar`,
`jnigen-loader`/`jnigen-commons` (chargement de la bibliothèque native) et
`jspecify` ; `build.xml` et `build.gradle.kts` les câblent,
`scripts/fetch_luajava.py` explique d'où ils viennent. `Main.runLuaScript()`
lit un fichier, fait `new Lua51()`, `lua.run(script)`, attrape `LuaException` et
l'imprime ; `hello-lua.lua` à la racine est le script de test. Aucune commande
clide n'y est bindée — et dans cette forme, aucune ne *peut* l'être : `Main`
s'exécute dans le JVM **client**, alors que le `ClideContext` (et avec lui la
`JdtlsSession`, la `TransactionStack`, le `projectRoot`, le `maxResults`)
n'existe que dans le daemon. Ce runtime vaut donc comme preuve que luajava se
charge, natifs compris, et rien de plus.

## Architecture : le runtime Lua dans le daemon

**Le runtime Lua vit dans le process du daemon**, chaque fonction Lua appelant
les commandes en Java par un point d'entrée partagé, sans repasser par le codec
texte « un token par ligne ». Plutôt qu'un client Lua externe parlant le
protocole texte : ce protocole est conçu pour un client texte bête (Claude
tapant au clavier), pas pour un langage de script, et un client externe devrait
reparser du texte pretty-printé pour en refaire une table Lua — fragile, et
redondant avec ce que `CommandPayload` rend inutile. En embarqué,
`CommandPayload` se convertit directement en table Lua.

Deux conséquences à assumer. D'abord, `ClideDaemon` sert ses clients
**strictement un à la fois** (boucle `accept()` séquentielle) : un script long
bloque toute autre connexion pendant sa durée. Acceptable pour un outil
mono-utilisateur, mais à dire. Ensuite, `printMode` et `maxResults` sont des
réglages **par connexion**, remis à zéro par `resetPerConnectionSettings()` : un
script hérite de ceux de la connexion qui l'a soumis. En particulier, un
`find_reference` appelé depuis Lua est tronqué à `maxResults` (100 par défaut)
comme n'importe quel autre — silencieusement, du point de vue d'un script qui ne
lirait que `items`.

## Soumettre un script : le flag `--lua` et le handshake socket

Côté client, un flag : `clide --lua <chemin du script> <chemin du projet>`.
Côté socket, un second handshake à côté de `--human`. Le client annonce « ce qui
suit est un script », envoie le contenu du fichier, puis ferme son côté écriture
; le daemon lit jusqu'à EOF et exécute.

**Ce que le client fait.** `Main` parse `--lua` et son argument comme il parse
`--human`, puis construit un `ClideClient` qui connaît le script — il ne fait
plus tourner de Lua lui-même. `ClideClient.announcePrintMode()` devient
l'annonce du mode de la connexion en général : elle envoie `--lua` comme
première ligne quand un script est soumis, `--human` quand c'est demandé, et
rien du tout en mode AI (le silence en mode AI est délibéré : le flux d'octets
d'une session AI reste une suite de commandes nue, sans préambule à filtrer, et
une première ligne qui n'est aucun des deux flags est traitée comme la commande
qu'elle est). Puis `relay()` pompe le contenu du fichier dans la socket au lieu
de `System.in`, et appelle `socket.shutdownOutput()` comme il le fait déjà —
c'est ce `shutdownOutput()` qui produit l'EOF que le daemon attend, la moitié
lecture de la socket restant ouverte pour recevoir la sortie du script.

**Ce que le daemon fait.** `ClideDaemon.readPrintMode()` devient la lecture du
mode de connexion : `--human` → mode HUMAN, `--lua` → mode script, toute autre
première ligne → mode AI, cette ligne étant alors déjà la première commande.
En mode script, `runSession()` ne boucle pas sur les commandes : il lit le reste
du flux jusqu'à EOF, l'exécute dans un `Lua51` dont les fonctions sont liées au
`ClideContext` de cette connexion, écrit la sortie sur la socket, et ferme. La
`JdtlsSession` et le daemon restent debout, comme après n'importe quelle
déconnexion.

**Points à ne pas rater dans cette voie :**

- **`print()` écrit sur le mauvais flux par défaut.** Le `print` de Lua écrit
  sur la sortie standard native du process — donc celle du *daemon*, redirigée
  vers `.clide/tmp/.clide-daemon.log`, pas sur la socket du client. Le pont doit
  remplacer `print` par une fonction écrivant sur le `PrintStream` de la
  connexion, sans quoi un script s'exécute correctement en n'affichant rien.
- **Une erreur Lua doit sortir dans l'enveloppe habituelle.** Une `LuaException`
  (script syntaxiquement invalide, erreur levée par une fonction bindée et non
  rattrapée) se rend au client sous la forme `?ERROR <CODE>: <message>` que
  `ResultEnvelope` produit déjà, avec un `ErrorCode` dédié — un client parse
  l'échec d'un script comme il parse tout le reste. Le message doit porter la
  ligne Lua fautive, que `LuaException` donne.
- **`--human` et `--lua` ensemble n'ont pas de sens** : les invites
  `> READY`/`> <paramètre> ?` s'adressent à quelqu'un qui tape. À refuser côté
  client, avec un message clair, plutôt qu'à faire cohabiter.
- **`exit`/`quit`/`terminate` n'ont pas à devenir des fonctions Lua.** Ce sont
  des commandes de contrôle de session : `exit` depuis un script arrêterait la
  `JdtlsSession` au milieu du script, `terminate` tuerait le daemon qui exécute
  ce script. Le script se termine en se terminant.
- **Le mode script n'est pas un `PrintMode`.** `PrintMode` dit comment un
  résultat s'écrit pour un lecteur (AI ou humain) ; une connexion Lua ne rend
  aucun résultat en texte, elle convertit des payloads. Le mode script est donc
  une branche de `runSession()`, et le `ClideContext` reste en `PrintMode.AI`
  pour le peu qui le consulte.

## Dispatch partagé : ne pas contourner les garde-fous

Les contrôles vivent dans `ClideDaemon.runSession()`, **au-dessus** de
`Command.executeCommand()`, pas dedans :

- résolution du mot-clé (`ClideContext.getCommand()`, sinon `UNKNOWN_KEYWORD`) ;
- lecture des paramètres et `MISSING_PARAMETERS` si l'entrée s'arrête en cours ;
- `validateParams()` → `validate()` par `ParamType` : `TRANSACTION_ID` contre
  `TransactionStack.ID_PATTERN`, `REGEX` qui doit compiler, `POSITION` qui doit
  parser *et* correspondre au contenu réel du fichier (`PositionParser.parse()`,
  qui vérifie que le nom commence bien à cette colonne, comme mot entier) ;
- `needsOpenTransaction()` → `NO_OPEN_TRANSACTION` ;
- `needsJdtlsSession()` → `ensureSessionReady()`, qui relance une session
  arrêtée par un `exit`/`quit` précédent.

Si le pont appelle `executeCommand()` en direct, un script peut modifier un
fichier hors transaction, passer une position jamais validée, ou toucher jdtls
sans session. **Extraire un point d'entrée partagé** (par exemple un
`CommandDispatcher.dispatch(ClideContext, Command, String[])`) que le protocole
texte *et* le pont appellent tous les deux est donc un prérequis, pas une
élégance : c'est la seule façon que les deux façades ne divergent pas avec le
temps. La lecture des paramètres (`readParams()`, le `MULTI_LINE` à terminateur)
reste spécifique au protocole texte et n'a pas à descendre dans ce point
partagé — Lua reçoit ses arguments déjà séparés.

## Conversion `CommandPayload` → table Lua

Le pont a besoin d'un convertisseur récursif payload → valeur Lua, **miroir de
`render()`** : là où `render()` produit du texte, il produit une table. Un
`switch` exhaustif sur les treize `CommandPayload`, plus la poignée de records
de `clide.model` qu'ils contiennent — `Listing<T>`, `Position(path, line,
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

**Conventions à fixer explicitement**, plutôt que laissées au hasard de la
première implémentation :

- une `Listing` devient-elle une table `{items = {...}, totalCount = n,
  truncated = bool}` (fidèle, verbeux) ou directement le tableau d'items avec
  les compteurs en clés à côté (pratique, moins régulier) ? Les deux exemples en
  tête de ce document supposent la première forme.
- une chaîne vide qui veut dire « ne s'applique pas » (le `path` de
  `CommandPayload.Transaction`, le `lineText` d'une `CodeLocation` illisible)
  reste-t-elle `""` côté Lua, ou devient-elle `nil` ? `""` est plus fidèle au
  Java et évite le piège de la clé qui disparaît d'une table.
- un `enum` Java (`TestOutcome.Status`, `Diagnostic.Severity`,
  `CommandPayload.Transaction.Action`) devient une chaîne Lua — laquelle, le
  `name()` brut ou une forme minuscule ? Choisir une fois.
- côté erreur, un `status == ERROR` lève-t-il une erreur Lua (donc `pcall` pour
  la rattraper) ou renvoie-t-il une table avec `code`/`hint` ? Le naturel en Lua
  est de renvoyer `nil, err` ou de lever ; à trancher avant d'écrire la première
  fonction, parce que tout le style des scripts en découle. `ErrorCode` compte
  trente-deux codes (plus `NONE`) et `hint` est souvent vide — un script décide
  sur `code`, jamais sur `message`.

## Génération des fonctions Lua

L'objectif est « une commande = une fonction Lua », avec la nuance ci-dessus.
Pour chaque `Command` de `CommandRepository` : une fonction Lua du même nom que
son `@Keyword`, d'arité `paramSize()`, dont les arguments sont convertis puis
validés selon `getParamTypes()` — la même validation que le protocole texte, via
le point de dispatch partagé — et dont le retour est le payload converti. Une
commande dont le payload a déjà son `case` n'a alors rien à écrire.

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

## `Position` : un deuxième point d'entrée pour Lua

`PositionParser.parse(String, Path)` attend un token unique
`<chemin>:<ligne>:<colonne>:<nom>`, pensé pour un client texte qui recopie tel
quel un résultat précédent. Un script a plutôt une table `{path, line, column,
name}` sous la main — celle que le convertisseur vient de lui donner. Lui faire
concaténer une chaîne pour la faire reparser serait un aller-retour inutile et
une source d'erreurs.

Attention à ce que ce raccourci ferait sauter. Le constructeur canonique de
`Position` ne vérifie qu'une chose : que le chemin est relatif au projet. Toute
la validation utile — le nom commence bien à cette colonne, comme mot entier, la
ligne existe, le fichier est lisible — vit dans `PositionParser.parse()`. La
javadoc de `Position` le signale comme un manque assumé (« PENDING ») : une
`Position` transportée et réutilisée en mémoire contourne entièrement cette
vérification et peut être devenue fausse. C'est ce que fait le second exemple
entre son `find_reference` et son `replace_symbol` ; le premier fait circuler
une `Position` de la même façon mais n'écrit rien entre les deux appels, ce qui
rend le raccourci sans danger là et dangereux ici. Piste : un
`PositionParser.of(path, line, column, name, projectRoot)` partageant la logique
de `parse()` sans passer par la sérialisation, et que le pont appelle à chaque
fois qu'une table Lua redevient une `Position`.

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

- **Sortie du script** : au-delà du `print()` redirigé sur la socket, la valeur
  de retour du script elle-même a-t-elle un sens à remonter, et sous quelle
  forme ?
- **Forme des tables** rendues par le convertisseur, `nil` vs `""`, forme des
  enums, erreur levée vs valeur de retour (voir « Conversion »).
- **`maxResults` côté Lua** : un script hérite du plafond de sa connexion et
  peut donc lire une liste tronquée sans le savoir. Le pont ignore-t-il le
  plafond, l'expose-t-il, ou laisse-t-il le script appeler `set_max_results` ?
- **Gestion d'erreur** : tout le script enveloppé dans un `pcall` par clide, avec
  rollback automatique de toute transaction encore ouverte sur exception non
  rattrapée ? Ou à la charge du script, comme dans le second exemple ?
- **Un `--dry-run` global** qui force le rollback quel que soit le script,
  indépendamment de sa propre logique — utile en CI ou pour explorer sans
  risque ?
- **`find_reference` après modification dans la même transaction** : index live
  ou snapshot (voir « Staleness »).
- **Sandboxing** : le script est-il restreint au DSL exposé (pas d'accès
  filesystem/réseau Lua générique), ou runtime complet ? Il tourne dans le
  process du daemon, ce qui rend la question moins théorique.
- **Transaction implicite** : une transaction ouverte/fermée automatiquement
  autour du script, ou explicite comme aujourd'hui ?
- **Garde-fous applicatifs** : seuil de fichiers touchés avant confirmation,
  refus de `commit_transaction` si l'arbre git n'est pas propre ?
- **Combinateurs de plus haut niveau** (`rename_symbol_where(predicate)`) dans
  le cœur de clide, ou en Lua pur dans une bibliothèque de scripts ?

## Pièges identifiés

- **`print()` part dans le log du daemon**, pas sur la socket du client, tant
  qu'il n'est pas remplacé par le pont — un script qui tourne sans rien afficher.
- **Le runtime actuel est dans le process client**, où aucun `ClideContext`
  n'existe : il ne peut binder aucune commande.
- **Les commandes de transaction sont commentées dans `CommandRepository`** :
  elles compilent et ont leurs payloads, mais ne sont pas atteignables.
- **Contournement des garde-fous** si le pont appelle `executeCommand()` sans
  point de dispatch partagé (`ParamType`, `needsOpenTransaction()`,
  `needsJdtlsSession()`).
- **Troncature silencieuse** : `maxResults` (100 par défaut) s'applique aux
  résultats vus depuis Lua comme depuis le texte. Un script qui lit `items` sans
  regarder `totalCount` travaille sur un sous-ensemble sans le savoir.
- **`Position` réutilisée en mémoire** : sa validation vit dans
  `PositionParser.parse()`, pas dans son constructeur — une position transportée
  d'un appel Lua au suivant n'est plus vérifiée contre le contenu du fichier.
- **`SymbolHit.location` est nullable** : l'ignorer ne produit pas une erreur,
  mais une réponse fausse.
- **Rename LSP ≠ rename partiel** : `textDocument/rename` est tout-ou-rien sur
  l'ensemble des références ; le besoin du second exemple est une substitution
  localisée. Deux commandes différentes, pas une seule avec un filtre.
- **Id de transaction** : `$` obligatoire en préfixe, `\w` minuscule ensuite
  (`TransactionStack.ID_PATTERN`, `ParamType.TRANSACTION_ID`). Le protocole
  texte répond `?ERROR INVALID_TRANSACTION_ID` ; il faut l'équivalent côté Lua —
  erreur explicite, jamais d'échec silencieux.
- **Un script long bloque le daemon**, qui sert ses clients un à la fois.
- **Une commande, deux formes de réponse selon un argument** : un paramètre
  optionnel qui change la forme du payload casse l'hypothèse « chaque commande
  déclare une forme » sur laquelle repose la génération des fonctions Lua — même
  fonction, deux tables différentes selon l'argument. À vérifier pour toute
  commande dont l'arité admettrait un paramètre optionnel.

## Prochaines étapes

Chaque étape valide la suivante. **Le pont n'attend pas la commande
d'écriture** : tout ce qu'il faut pour faire tourner un script utile de bout en
bout est déjà enregistré.

**Phase 1 — faire tourner le premier exemple (lecture seule)**

1. **Extraire le point de dispatch partagé** portant les garde-fous aujourd'hui
   dans `ClideDaemon.runSession()` (`ParamType`, `needsOpenTransaction()`,
   `needsJdtlsSession()`), appelé par le protocole texte comme par le pont.
2. **Câbler `--lua` et son handshake** : flag côté `Main`, envoi du script par
   `ClideClient` suivi de `shutdownOutput()`, branche « mode script » dans
   `ClideDaemon.readPrintMode()`/`runSession()`, `print` redirigé sur la socket,
   `ErrorCode` dédié pour une `LuaException`.
3. **Brancher trois fonctions** — `list_members`, `find_reference`,
   `set_max_results` — avec les convertisseurs de `Symbols`, `Locations` et
   `Setting`, plus `PositionParser.of(...)` pour qu'une table Lua redevienne une
   `Position` validée. Ajouter `rebuild` (payload `Rebuild`) complète le premier
   exemple, qui devient le test d'acceptation du pont.
4. **Généraliser** par réflexion sur `CommandRepository`, en complétant le
   convertisseur payload par payload — le compilateur signalant chaque forme
   encore non traitée.

**Phase 2 — l'écriture**

5. **Réenregistrer les six commandes de transaction** dans `CommandRepository`
   (ou écrire pourquoi elles sont désactivées, si c'est délibéré).
6. **Écrire `replace_symbol`**, la première vraie commande de modification, sur
   les primitives existantes (`Position`, `TransactionStack`).
7. **Faire tourner le second exemple**, et trancher au passage ce que seul un
   script qui écrit oblige à trancher : `pcall` et rollback automatique,
   `--dry-run`, index live ou snapshot après écriture.
