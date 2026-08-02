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

## Architecture d'intégration : deux options, non tranchées

**Option A — client Lua externe**, parlant le protocole texte existant
(socket/stdin vers le daemon), en implémentant côté Lua le codec « un token
par ligne » (voir « Syntaxe des commandes » dans `CLAUDE.md`) plus le
protocole à terminateur de `ParamType.MULTI_LINE`. Avantage : aucun
couplage avec l'interne Java, clide continue de n'avoir qu'une seule surface
(le protocole texte), utilisable par n'importe quel langage. Inconvénient :
sérialisation dans les deux sens, et il faudrait reparser du texte
pretty-printé pour en refaire une table Lua (`chemin/relatif.java:ligne:
contenu` → structure) — fragile et redondant avec le travail déjà fait côté
Java pour produire ce texte.

**Option B — luajava embarqué dans le process clide**, chaque fonction Lua
appelant directement `Command.executeCommand(ClideContext, ...)` (ou un
point d'entrée partagé, voir plus bas) sans repasser par `readParams()` ni
le parsing ligne par ligne. Avantage : retour structuré nativement (objets
Java → tables Lua), pas de sérialisation texte, cohérent avec le principe
« une commande = une fonction Lua » explicitement demandé. Inconvénient :
couple le cycle de vie du runtime Lua à celui du daemon, et suppose que
`CommandResult` porte une charge utile structurée en plus du texte (voir
section suivante) — ce qui n'est pas le cas aujourd'hui.

Le protocole texte « un token par ligne » n'a de sens que pour l'option A —
inutile de le réimplémenter côté Lua si l'option B est retenue, puisqu'il a
été conçu pour un client texte bête (Claude tapant au clavier), pas pour un
langage de script.

**Non tranché : quelle option retenir.** L'option B semble mieux coller à
« une commande = une fonction Lua », mais implique plus de refactoring
préalable (voir ci-dessous) que l'option A.

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
`Command.executeCommand()`, pas dedans. Si un pont Lua (option B) appelle
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

- Option A (client externe, protocole texte) vs option B (luajava
  embarqué, appel direct à `executeCommand()`) — non tranché.
- Forme exacte de `CommandData` : quelles implémentations concrètes,
  commande par commande.
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
  commandes continue de grossir.
- **Protocole texte « un token par ligne » + `MULTI_LINE` à terminateur**
  n'a de sens qu'en option A — ne pas le réimplémenter côté Lua si
  l'option B est retenue.

## Prochaines étapes envisagées (non implémentées)

- Trancher option A vs option B.
- Dessiner la forme exacte de `CommandData` (interface scellée + premières
  implémentations concrètes) et le nouveau `CommandResult`.
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
