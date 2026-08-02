# LUA.md — Scripter clide en Lua

Discussion initiée 2026-08-02, à ce stade pure réflexion/architecture — rien
d'implémenté. Ce document sert de point de départ aux conversations futures
sur le sujet : ce qu'on veut faire, ce qui existe déjà et sur quoi s'appuyer,
les refactorings à envisager, les questions non tranchées, les pièges
identifiés.

## Objectif

Aujourd'hui, clide se pilote commande par commande : Claude tape une
commande, lit la réponse, décide de la suivante — voir « État actuel » dans
`CLAUDE.md`. Ce mode reste le mode par défaut et ne disparaît pas.

L'idée est d'ajouter un second mode : des scripts **Lua**, capables
d'enchaîner plusieurs commandes clide sans repasser par un tour de décision
de Claude entre chacune — utile pour des refactors mécaniques et bien
définis, où la logique (filtrer, boucler, décider commit/rollback) est plus
naturelle à écrire d'un coup qu'à piloter pas à pas.

**Les deux modes coexistent, aucun ne remplace l'autre.** Claude choisit,
commande par commande ou script Lua, selon la tâche. Ça pousse à concevoir
toute évolution du cœur de clide (voir plus bas, `CommandResult` en
particulier) pour servir les deux façades à la fois, sans dupliquer la
logique métier ni laisser l'une contourner les garde-fous de l'autre.

## Exemple de script visé

Exemple de départ de la discussion (corrigé : l'id de transaction doit
commencer par `$`, voir plus bas) :

```lua
-- Renommer un ancien nom de méthode, mais seulement dans les fichiers de test
local refs = find_reference("src/main/java/net/sourceforge/plantuml/Foo.java:42:legacyCompute")
open_transaction("$rename_legacy_compute")
local touched = 0
for _, ref in ipairs(refs) do
  if ref.file:match("Test%.java$") then
    rename_symbol(ref.file, ref.line, "legacyCompute", "computeLegacy")
    touched = touched + 1
  end
end
if touched > 0 then
  local diff = diff_transaction("$rename_legacy_compute")
  print(string.format("%d fichiers de test modifiés", touched))
  commit_transaction("$rename_legacy_compute")
else
  rollback_transaction("$rename_legacy_compute")
  print("Aucun appelant trouvé dans les tests, rien à faire")
end
```

Ce script sert de cas de référence pour toute la réflexion ci-dessous : il
suppose `find_reference` renvoyant une table Lua itérable (`ref.file`,
`ref.line`), et une commande de modification (`rename_symbol` dans
l'exemple) qui n'existe pas encore aujourd'hui — voir « Ce qui manque ».

## Ce qui existe déjà et sur quoi s'appuyer

- **Les transactions** (`open_transaction`/`commit_transaction`/
  `rollback_transaction`/`diff_transaction`/`restore_file`, voir la section
  dédiée de `CLAUDE.md`) existent déjà, quasiment telles que l'exemple les
  utilise : sous-transactions imbriquées en pile, politique « premier
  backup gagne », `restore_file` pour annuler un seul fichier,
  `refuseIfDirty()` au démarrage si le daemon a planté en cours de
  transaction. Rien à construire ici, seulement à consommer depuis Lua.
- **`find_reference`/`find_declaration`/`find_implementation`/`find_symbol`/
  `hover`/`list_members`** existent et sont testées de bout en bout — voir
  `CLAUDE.md`. Ce sont les briques de lecture, déjà prêtes à être exposées
  comme fonctions Lua le jour venu.
- **Le pattern Command** (`clide.core.Command`, `CommandRegistry`,
  métadonnées `@Keyword`/`@Param`/`@Help` lues par réflexion) est déjà
  utilisé pour générer `help`/`help_ai` sans code spécifique par commande.
  C'est le mécanisme naturel à réutiliser pour générer les fonctions Lua
  automatiquement (voir plus bas) plutôt que d'écrire un binding à la main
  par commande.

## Ce qui manque : la vraie commande de modification

**Aucune commande ne modifie un fichier aujourd'hui** — `CLAUDE.md` le note
explicitement, les transactions sont prêtes et attendent la première
commande de ce genre.

Point important dégagé en discutant de l'exemple : ce que le script veut
faire (« renommer seulement dans les fichiers de test, laisser le code de
prod intact ») **n'est pas un rename sémantique au sens LSP/IDE**. Un vrai
`textDocument/rename` LSP renvoie un `WorkspaceEdit` qui couvre *toutes* les
occurrences d'un coup — pas de notion de sous-ensemble de fichiers. Le
besoin réel est plus bas niveau : une substitution textuelle appliquée
individuellement, référence par référence, à un sous-ensemble choisi par le
script.

Ça pointe vers une primitive plus simple que « rename_symbol partout » :
quelque chose comme `replace_symbol <symbole> <nouveau nom>`, opérant sur un
seul `Symbol` (fichier:ligne:nom) à la fois, en pure substitution textuelle
locale — sans appel LSP pour l'écriture elle-même (la position vient déjà de
`find_reference`). Toute la mécanique existe déjà pour la construire :
`Symbol.parse()` valide déjà que le nom est un mot entier à cette position,
`Transaction.backupBeforeModification()` existe déjà pour la sauvegarde
avant écriture. Il ne manque que la substitution + l'écriture sur disque.

Rappel de `JAVALENSE.md` (idée n°6, « refactorings = text edits, jamais
d'écriture directe ») : leur pattern est de toujours proposer un diff avant
d'écrire, jamais d'écrire en direct. C'est déjà essentiellement ce que le
couple `diff_transaction`/`commit_transaction` offre côté clide — le jour où
une commande d'édition existe, elle devrait backup + écrire dans la
transaction ouverte, laissant `diff_transaction` être le point où on
« propose » avant de committer.

## Architecture d'intégration : luajava embarqué (décidé)

**Décision (2026-08-02) : luajava embarqué dans le process clide**, chaque
fonction Lua appelant directement `Command.executeCommand(ClideContext,
...)` (ou un point d'entrée partagé, voir « Dispatch partagé » plus bas)
sans repasser par `readParams()` ni le parsing ligne par ligne du protocole
texte. Retenue plutôt qu'un client Lua externe parlant le protocole texte
existant (socket/stdin, codec « un token par ligne », terminateur de
`ParamType.MULTI_LINE`) : ce protocole a été conçu pour un client texte bête
(Claude tapant au clavier), pas pour un langage de script, et un client
externe aurait dû reparser du texte pretty-printé pour en refaire une table
Lua — fragile et redondant avec le travail déjà fait côté Java pour produire
ce texte. luajava embarqué donne un retour structuré nativement (objets Java
→ tables Lua), cohérent avec le principe « une commande = une fonction
Lua ».

Implication à assumer : ça couple le cycle de vie du runtime Lua à celui du
daemon, et ça suppose que `CommandResult` porte une charge utile structurée
en plus du texte (voir section suivante) — ce qui n'est pas encore le cas
aujourd'hui.

Le protocole texte « un token par ligne » + `MULTI_LINE` à terminateur reste
nécessaire pour le protocole texte existant (Claude au clavier), mais n'a
plus lieu d'être réimplémenté côté Lua.

## `CommandResult` : le refactor à envisager

Aujourd'hui (`clide.core.CommandResult`) : un record à deux champs,
`status` (`OK`/`ERROR`) et `message` — une chaîne déjà formatée pour
l'affichage humain/texte. Suffisant pour le protocole texte actuel, mais
toute l'information est déjà écrasée en prose au moment où `CommandResult`
existe : rien à réextraire proprement côté Lua.

`JAVALENSE.md` (idée n°2, « enveloppe de réponse pensée pour l'agent »)
propose une enveloppe `success`/`data`/`error{code, message, hint}`/
`meta{totalCount, returnedCount, truncated, suggestedNextTools}`. Piste de
traduction dans le vocabulaire clide :

- `status`/`message` inchangés — zéro régression sur le protocole texte,
  qui continue à n'utiliser que ces deux champs comme aujourd'hui.
- un `data` optionnel portant la charge utile structurée pour qui sait la
  lire (Lua aujourd'hui, une éventuelle façade JSON plus tard).
- côté `ERROR` seulement, un détail structuré (`code`, `hint`) plutôt qu'un
  message brut à parser — un script Lua pourrait alors décider sur `code`
  plutôt que sur du texte, et un `hint` guiderait la prochaine action utile
  (repris de JAVALENSE, ex. « Call health_check to monitor loading
  status »).
- un `meta` optionnel pour les commandes qui renvoient des listes
  (`find_reference`, `find_symbol`, `list_members`), avec `totalCount`/
  `truncated` — comble au passage un trou déjà signalé dans
  `JAVALENSE.md` : « les commandes clide ne bornent ni ne comptent : un
  `find_reference` sur un symbole très utilisé de PlantUML renvoie tout,
  sans indication ».

**Forme de `data` — cohérence avec le style déjà établi dans clide.**
Plutôt qu'un `Object`/`Map<String,Object>` (souple mais non typé, rien ne
garantit à la compilation que le bon convertisseur Lua existe pour la bonne
commande), le réflexe déjà présent dans clide est de réifier chaque concept
en petite classe dédiée (`Symbol`, `Transaction`/`TransactionStack`,
`Column`/`Cell`/`Row` pour `TextTable`). Piste cohérente : une interface
scellée `CommandData` avec une poignée d'implémentations concrètes (liste de
locations, résultat de diff, résumé de run de tests, « rien » pour
`exit`/`commit_transaction`…), et un convertisseur Lua unique qui fait un
`switch` exhaustif dessus — générique du point de vue du pont Lua, tout en
gardant la garantie à la compilation que chaque commande déclare une forme
que ce convertisseur sait traiter. Non tranché : le détail des
implémentations concrètes de `CommandData` n'a pas encore été listé
commande par commande.

## `Monomorphic` comme forme de `data`

(2026-08-02, mis à jour 2026-08-02) Piste concrète pour la question laissée
ouverte ci-dessus : plutôt qu'une nouvelle interface scellée `CommandData` à
dessiner de zéro, réutiliser `Monomorphic`, qui existe déjà (née pour
modéliser les valeurs JSON-RPC côté `LspClient`). `Monomorphic` couvre
exactement les sept formes qu'un `CommandResult.data()` a besoin de porter
(`NULL`/`BOOLEAN`/`STRING`/`INTEGER`/`DECIMAL`/`LIST`/`MAP`), immuable, et sa
dualité `LIST`/`MAP` correspond terme à terme à la dualité tableau/table-à-
clés d'une table Lua — une `LIST` devient une table indexée à partir de 1,
une `MAP` devient une table à clés chaînes, les scalaires passent tels
quels. Un seul convertisseur récursif `Monomorphic` → valeur Lua (et son
inverse) suffirait pour toutes les commandes, au lieu d'un binding par forme
de résultat.

**Mise à jour : `Monomorphic` a déménagé.** `Monomorphic`/`MonomorphicType`
(et `Json`, le codec JSON-RPC qui les lit/écrit) vivent maintenant dans un
package neutre `clide.json`, sorti de `clide.jdtls` — voir « Problème de
couches » ci-dessous, désormais résolu par ce déplacement plutôt
qu'en suspens.

**Le compromis que ça réintroduit.** La section précédente écartait
`Object`/`Map<String,Object>` justement parce que « rien ne garantit à la
compilation que le bon convertisseur existe pour la bonne commande ».
`Monomorphic` règle la sécurité de forme (jamais de cast sauvage, jamais de
`ClassCastException` à la lecture), mais pas la sécurité par commande : rien
n'empêche `find_reference` de nommer une clé `"file"` un jour et `"path"` un
autre — le compilateur ne verra rien, contrairement à ce qu'aurait donné une
interface scellée avec un type dédié par commande. Recours possible plus
tard sans revenir sur le format de transport : poser de petits accesseurs
typés par commande au-dessus de `Monomorphic` (des enregistrements qui font
`getFromMap("file")` en interne) — `Monomorphic` restant le fil, pas l'API.

**Problème de couches — résolu par le déplacement.** `clide.core.Command`
expose déjà `needsJdtlsSession()` — une convention délibérée : `core`
connaît jdtls *conceptuellement* (un booléen), mais n'importe jamais ses
types. Faire dépendre `clide.core.CommandResult` de `clide.jdtls.Monomorphic`
aurait inversé cette règle. C'est désormais sans objet : `Monomorphic`
vivait déjà dans les faits un rôle plus générique que « valeur de réponse
LSP » — `FindReferenceCommand` et `PositionCommandSupport` (dans
`clide.command`) l'utilisaient déjà comme constructeur générique de valeur
structurée pour les paramètres de requête (`Monomorphic.mapBuilder()
.putBoolean(...)`), pas seulement pour parser les réponses — et le
déplacement de `Monomorphic`/`MonomorphicType`/`Json` vers `clide.json`
l'acte formellement : `clide.core` peut désormais dépendre de
`clide.json.Monomorphic` sans jamais importer `clide.jdtls`, la convention
« `core` ignore les types de jdtls » reste intacte. Note : le codec `Json`
(parsing/écriture des octets JSON-RPC sur le fil) a été déplacé avec
`Monomorphic` plutôt que laissé dans `jdtls` comme d'abord envisagé ici —
sans conséquence pour ce choix de couches, puisque `clide.core` n'aurait de
toute façon besoin que de `Monomorphic`, jamais du codec.

Point de vigilance à vérifier après tout déplacement de ce genre : les sites
d'utilisation existants doivent suivre le changement de package — deux
`import clide.jdtls.Monomorphic;` avaient été oubliés dans
`FindReferenceCommand`/`PositionCommandSupport` lors de ce déplacement
(corrigés en marge de cette mise à jour de `LUA.md`).

**Un trou plus profond que `CommandResult`.**
`PositionCommandSupport.goToAndFormat()` s'appuie sur
`JdtlsSession.goToPosition()`, qui renvoie déjà un `List<String>` — des
lignes `"chemin:ligne: contenu"` pré-formatées — et
`PositionCommandSupport.format()` ne fait plus que construire le message
texte à partir de ce texte déjà aplati. Câbler `Monomorphic` dans
`CommandResult.data()` ne suffit donc pas à lui seul à donner à
`find_reference` un résultat structuré : l'écrasement en prose se produit
une couche plus bas, avant même d'arriver à `CommandResult`. Il faudrait que
`JdtlsSession` (ou l'appelant) conserve/reconstruise la structure — une
liste de positions, ou directement une `Monomorphic` LIST — et que
`format()` construise en parallèle le message texte *et* le payload
structuré à partir de cette même source structurée, plutôt qu'à partir du
`List<String>` déjà aplati par `goToPosition()`.

**Le piège `null` côté Lua.** Une `Monomorphic` MAP contenant une entrée
`NULL`, ou une LIST avec un `NULL` dedans, ne se traduit pas par un `nil`
Lua ordinaire dans une table : assigner `nil` à une clé la supprime, et une
liste avec un « trou » casse `#`/`ipairs`. Le convertisseur aura besoin d'un
sentinel dans les deux sens (comme `cjson.null`). Dans l'autre sens (un
script qui construirait une `Monomorphic` à passer à une future commande
d'édition), la même ambiguïté tableau-vide-vs-objet-vide qu'en JSON se pose
pour une table Lua `{}` — à trancher explicitement par convention plutôt que
laissé implicite.

**Simplification de l'enveloppe d'erreur.** Le `code`/`hint` structuré
proposé plus haut pour le cas `ERROR`, à côté d'un `data` distinct pour le
succès, devient inutile comme structure séparée : une `Monomorphic` MAP
avec les clés `code`/`hint` sert aussi bien pour l'erreur que pour un
résultat de succès — même représentation, même chemin de lecture côté Lua
(`result.data.code`). `CommandResult` resterait à trois champs (`status`,
`message`, `data`) plutôt que quatre. Cohérent aussi avec la discipline déjà
en place sur `message` (jamais `null`) : `data` pourrait suivre la même
règle en défaut à `Monomorphic.createNull()` plutôt qu'à un `null` Java — la
classe le prévoit déjà explicitement dans sa doc (« an absent value is
`createNull()` »).

## Génération des fonctions Lua : réutiliser la réflexion existante

Pour que « une commande = une fonction Lua » ne demande pas d'écrire un
binding à la main par commande : s'appuyer sur la même méta-donnée que
`help`/`help_ai` (annotations `@Keyword`/`@Param`/`@Help` sur les
`Command`). Pour chaque `Command` de `CommandRegistry`, générer
automatiquement une fonction Lua du même nom, de même arité que
`getParamTypes()`, qui convertit les arguments Lua reçus, exécute la
commande, et convertit `CommandResult.data()` en valeur Lua (ou lève une
erreur Lua structurée depuis `code`/`hint` si `status == ERROR`). Une
nouvelle commande Java obtiendrait alors sa fonction Lua « gratuitement »,
à condition de peupler correctement son `data`.

## Dispatch partagé : ne pas contourner les garde-fous existants

Piège identifié : `needsOpenTransaction()`/`needsJdtlsSession()` et la
validation `ParamType` (`ClideDaemon.validate()`) vivent aujourd'hui dans la
boucle du daemon texte (`ClideDaemon.runSession()`), **au-dessus** de
`Command.executeCommand()`, pas dedans. Si le pont Lua appelle
`executeCommand()` en direct pour éviter la sérialisation, ces contrôles
doivent absolument rester sur le chemin — sinon un script Lua pourrait par
exemple modifier un fichier hors transaction, ou passer un symbole jamais
validé.

Piste : extraire un point d'entrée partagé (ex. `CommandRegistry.dispatch(
ClideContext, Command, args)`) que le protocole texte **et** le futur pont
Lua appellent tous les deux, plutôt que de laisser chaque façade
réimplémenter ses propres garde-fous et risquer qu'ils divergent avec le
temps.

## `Symbol` : un deuxième point d'entrée pour Lua

`Symbol.parse(String, Path)` attend une chaîne unique `<chemin>:<ligne>:
<nom>`, pensée pour un client texte qui recopie tel quel un résultat
précédent (voir « Notation… » dans `CLAUDE.md`). Un script Lua a plutôt un
fichier, une ligne (entier) et un nom séparément sous la main — lui faire
construire une chaîne à concaténer puis reparsée serait un aller-retour
inutile et une source d'erreurs (échappement, séparateurs). Piste : un
`Symbol.of(file, line, name, projectRoot)` partageant la même logique de
validation interne (mot entier `\bnom\b`, bornes de ligne, fichier
existant) que `Symbol.parse()`, sans repasser par la sérialisation texte.

## Rappel de syntaxe : id de transaction

`TransactionStack.ID_PATTERN` impose qu'un id commence par `$`, suivi de
caractères `\w` en minuscule (`ParamType.TRANSACTION_ID`). L'exemple de
script plus haut a été corrigé en conséquence
(`open_transaction("$rename_legacy_compute")`). À documenter clairement
dans l'aide/les erreurs côté Lua le jour venu — l'erreur `?SYNTAX ERROR` du
protocole texte est claire sur ce point, il faudra l'équivalent côté Lua
(exception avec message explicite, pas un échec silencieux).

## Staleness de l'index : plus critique en mode script

`JAVALENSE.md` (idée n°1, `DiskStampService`) identifie déjà l'angle mort
actuel de clide : Claude édite les fichiers *en dehors* de clide
(Write/Edit directs), donc le modèle jdtls construit au démarrage du daemon
devient périmé silencieusement, sans aucun signal. En mode conversationnel
tour par tour, un humain (ou Claude) a une chance de remarquer une réponse
qui sent le rassis. **En mode script Lua batché, ce risque grandit** : un
script qui enchaîne modification → nouvelle requête → modification sans
jamais rendre la main entre deux étapes est exactement le scénario où un
index périmé ferait le plus de dégâts, sans personne pour remarquer que les
résultats intermédiaires sont faux. Le pont Lua et le `DiskStampService`
(ou équivalent) se justifient l'un l'autre plus qu'ils ne sont indépendants
— à garder en tête dans l'ordre de priorité des chantiers.

Question dérivée, non tranchée : au sein d'une même transaction Lua, un
`find_reference` appelé après un `replace_symbol` doit-il voir l'état déjà
modifié (index « live ») ou rester figé sur l'état d'avant la transaction
(snapshot) ? Les deux ont un sens selon le script ; le choix doit être
documenté explicitement, pas laissé implicite.

## Round-trips : composite commands vs composition en Lua

`JAVALENSE.md` (idée n°3) motive ses commandes composées (`analyze_type`,
`analyze_method`…) par la réduction des allers-retours d'agent — chaque
tour coûte cher en mode conversationnel. Cette motivation s'affaiblit
fortement une fois qu'un script Lua existe : à l'intérieur d'un script, un
appel de fonction Lua est quasi gratuit, la composition se fait déjà dans
le script. Piste de partage des rôles : garder les commandes composées
côté texte pour le mode tour par tour, garder les primitives Lua fines et
laisser la composition aux scripts plutôt que de dupliquer la logique de
composition côté serveur.

## Questions ouvertes

- Forme de `data` : piste retenue vers `Monomorphic` (désormais dans
  `clide.json`, package neutre — voir « `Monomorphic` comme forme de
  `data` ») plutôt qu'une nouvelle interface scellée `CommandData` — reste à
  trancher entre les deux, et, `Monomorphic` retenu ou non, à refaire
  remonter la structure aujourd'hui perdue dans
  `JdtlsSession.goToPosition()`/`PositionCommandSupport.format()` avant
  qu'elle n'atteigne `CommandResult`.
- Sentinel pour `null` et convention tableau-vide/objet-vide (`{}` côté Lua)
  pour la conversion `Monomorphic` ↔ table Lua — non tranché.
- Gestion d'erreur au sein d'un script : tout le script enveloppé dans un
  `pcall` par clide, avec rollback automatique de toute transaction encore
  ouverte sur exception non rattrapée ? Ou à la charge du script (comme
  dans l'exemple actuel, qui gère lui-même le `if touched > 0 …
  else rollback`) ?
- Un flag global type `--dry-run` qui force le rollback quel que soit le
  script, indépendamment de sa propre logique — utile en CI ou pour
  explorer sans risque ?
- `find_reference` après modification dans la même transaction : index
  live ou snapshot (voir « Staleness » ci-dessus) ?
- Sandboxing du Lua : le script doit-il être restreint au DSL exposé
  (pas d'accès filesystem/réseau Lua générique), ou runtime Lua complet ?
- Une transaction = un bloc Lua implicite (ouverte/fermée automatiquement
  autour du script), ou explicite comme aujourd'hui (`open_transaction`/
  `commit_transaction` à la charge du script) ?
- Garde-fous de sécurité applicatifs : seuil de fichiers touchés avant
  d'exiger une confirmation, refus de `commit_transaction` si l'arbre git
  n'est pas propre ?
- Faut-il des combinateurs de plus haut niveau (ex. `rename_symbol_where
  (predicate)`) en plus de la boucle manuelle, ou rester au niveau primitif
  et laisser ces combinateurs vivre en Lua pur (bibliothèque de scripts,
  pas le cœur de clide) ?

## Pièges identifiés

- **Identité du symbole ambiguë par nom + ligne rappelés en boucle** : dans
  l'exemple, `rename_symbol(ref.file, ref.line, "legacyCompute", ...)`
  repasse le nom en clair après l'avoir déjà localisé une fois — si deux
  symboles homonymes coexistent sur la même ligne/fichier (peu probable
  mais possible), mieux vaut faire circuler un identifiant stable
  (l'équivalent d'un `Symbol` déjà résolu) plutôt que de re-matcher par nom
  à chaque appel.
- **Rename LSP ≠ rename partiel** : ne pas confondre un futur
  `rename_symbol` façon refactoring IDE (`textDocument/rename`,
  tout-ou-rien sur l'ensemble des références) avec le besoin réel de
  l'exemple, qui est une substitution textuelle localisée
  (`replace_symbol`, voir plus haut) — ce sont deux commandes différentes,
  pas une seule avec un paramètre de filtre.
- **Id de transaction** : `$` obligatoire en préfixe, uniquement `\w`
  minuscule ensuite — piège de syntaxe simple mais bloquant si oublié côté
  Lua.
- **Contournement des garde-fous** si le pont Lua appelle
  `executeCommand()` sans passer par un point de dispatch partagé avec le
  protocole texte (`needsOpenTransaction()`/`needsJdtlsSession()`/
  validation `ParamType`) — voir « Dispatch partagé » plus haut.
- **Staleness de l'index jdtls** en enchaînement modifie → requête sans
  mécanisme de vérification disque (voir « Staleness » plus haut) — plus
  dangereux en script batché qu'en mode conversationnel.
- **`data` non typé** (`Object`/`Map` générique) sacrifierait la sécurité
  de compilation entre les ~15 commandes existantes et leurs formes de
  résultat différentes — préférer une interface scellée si le volume de
  commandes continue de grossir. Nuance depuis « `Monomorphic` comme forme
  de `data` » : `Monomorphic` évite le cast sauvage (sécurité de *forme*)
  mais n'apporte pas la sécurité *par commande* qu'aurait donnée une
  interface scellée — un typo de clé dans un `mapBuilder().put(...)` ne sera
  jamais détecté à la compilation.
- **`null` dans une table Lua** : assigner `nil` à une clé de table la
  supprime, une `Monomorphic` NULL ne peut donc pas se traduire par un
  `nil` Lua brut — nécessite un sentinel explicite dans le convertisseur
  (voir « `Monomorphic` comme forme de `data` »).
- **`JdtlsSession.goToPosition()` aplatit déjà en `List<String>`** avant
  même `CommandResult` — câbler `Monomorphic` dans `CommandResult.data()`
  seul ne suffira pas à donner à `find_reference` un résultat structuré tant
  que cette couche n'est pas revue.
- **Protocole texte « un token par ligne » + `MULTI_LINE` à terminateur** :
  conçu pour le client texte du daemon (Claude au clavier) — ne pas le
  réimplémenter côté Lua, luajava embarqué n'en a pas besoin (voir
  « Architecture d'intégration »).
- **Site d'utilisation oublié lors d'un déplacement de package** :
  `Monomorphic`/`Json`/`MonomorphicType` déplacés de `clide.jdtls` vers
  `clide.json` sans mettre à jour les imports dans
  `FindReferenceCommand`/`PositionCommandSupport` — cassait la compilation
  jusqu'à correction. À vérifier systématiquement après tout renommage de
  package (`grep` sur l'ancien chemin d'import avant de committer).

## Prochaines étapes envisagées (non implémentées)

- Trancher la forme de `data` : `Monomorphic` (désormais dans `clide.json`)
  réutilisé tel quel (voir « `Monomorphic` comme forme de `data` ») vs
  interface scellée `CommandData` dédiée.
- Revoir `JdtlsSession.goToPosition()`/`PositionCommandSupport.format()`
  pour arrêter d'aplatir le résultat en `List<String>` avant qu'il
  n'atteigne `CommandResult` — sans quoi `data` reste vide pour les
  commandes de type `find_*` même une fois `CommandResult` étendu.
- Extraire un point de dispatch partagé entre protocole texte et futur
  pont Lua, portant les garde-fous aujourd'hui dans `ClideDaemon`.
- Écrire la première vraie commande de modification (`replace_symbol`),
  sur les primitives déjà existantes (`Symbol`, `Transaction`) — c'est
  elle qui validera concrètement le design de `CommandData`/`CommandResult`
  avant de le généraliser aux commandes de lecture.
- Une fois une commande de modification en place : prototyper une seule
  fonction Lua de bout en bout (`find_reference` ou la nouvelle commande
  d'édition) avant de généraliser à toutes les commandes via la réflexion
  sur `CommandRegistry`.
