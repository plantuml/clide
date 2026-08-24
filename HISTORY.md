# clide — Command Line IDE

## Origine et objectif

Ce projet est né d'une discussion avec Claude à propos de PlantUML. Aujourd'hui,
Claude navigue dans une base Java (comme PlantUML) essentiellement par grep :
purement textuel, mais aveugle à l'héritage, aux overrides, au polymorphisme,
et incapable de savoir si le code qu'il vient d'écrire compile réellement.

`clide` doit combler cet écart, sans essayer de reproduire un IDE graphique.
Pas d'autocomplétion, pas de debugger à breakpoints : ces fonctionnalités sont
pensées pour un humain qui tape caractère par caractère et avance pas à pas,
ce qui ne correspond pas à la façon dont Claude travaille (par tours, avec du
code déjà écrit en entier).

Ce qui compte réellement, par ordre de priorité :

1. **Compiler** et récupérer la liste d'erreurs — supprime toute une classe de
   bugs (imports manquants, signatures qui ne matchent pas, refactor
   incomplet) que Claude fait autrement relire par l'humain.
2. **Lancer un test ciblé**, isolé, pas le build complet.
3. **Requêtes sémantiques** : qui appelle cette méthode, qui implémente cette
   interface, où est la vraie définition — typiquement via un moteur comme
   Eclipse JDT Language Server (jdtls), piloté en ligne de commande.

État de ces trois points : le 1 est couvert par la commande `rebuild` (non
détaillée dans ce document — voir `TESTS.md`, campagne 3), le 2 par
`run_test`/`run_tests` (voir « Lancer les tests du projet ouvert » plus bas),
le 3 par les commandes `find_*`/`hover`/`list_members`.

## Conventions de code

- Indentation : tabulations, pas d'espaces.
- Accolades :
  - Accolade ouvrante sur la même ligne que l'instruction.
  - `if`/`for`/`while` avec une seule instruction : pas d'accolades,
    l'instruction sur la ligne suivante, indentée.
  - Bloc de plusieurs instructions : accolades obligatoires, ouvrante sur la
    même ligne.
- Imports explicites, jamais de wildcard (`import java.util.*` interdit).
- Variables locales `final` par défaut, dès que possible.
- Négation de booléen : préférer une condition positive (`foo == false`)
  plutôt que l'opérateur de négation (`!foo`).

## État actuel

Gradle (Kotlin DSL), calqué sur la configuration du wrapper de PlantUML
(Gradle 9.3.1) :

- `clide` démarre et lit des commandes textuelles sur l'entrée standard.
- Le jdtls du projet est démarré et buildé automatiquement dès le premier
  `clide <chemin>` sur ce projet (le process qui gère ça — un par projet —
  tourne en arrière-plan et reste up pour les lancements suivants) : il n'y a
  pas de commande séparée à taper pour l'ouvrir, ni de notion de « projet
  courant » à changer.
- clide génère systématiquement son propre `.project`/`.classpath` à chaque
  démarrage de daemon (« invisible project », marqueur
  `__CREATED_BY_JAVA_LANGUAGE_SERVER__` dans le filtre du `.project` généré)
  — dossiers sources détectés depuis l'arborescence (`src/main/java`,
  `src/main/resources`, `src/test/...`), et chaque jar déposé dans `.clide/`
  à la racine du projet ajouté comme bibliothèque. Les dossiers de test
  (`src/test/java`, `src/test/resources`) sont marqués `test="true"` et
  reçoivent leur propre dossier de sortie `bin/test`, le code de production
  allant dans le `bin/main` par défaut — voir « Le `.classpath` généré et les
  dossiers de test » plus bas, où l'on explique pourquoi ce détail n'en est
  pas un. Si le projet avait déjà son propre `.project`/`.classpath`, il est
  remis en place tel quel une fois le build initial terminé — jamais modifié
  ni écrasé sur disque, seulement mis de côté le temps de l'import ; voir
  « `.project`/`.classpath` : ne jamais toucher aux fichiers du projet » plus
  bas pour le mécanisme (`EclipseProjectFiles`) et pourquoi c'est sans risque.
  Le daemon le signale dans sa trace de démarrage : `(4/4) Building project
  ... [OK] (imported via a temporary .project/.classpath from src/**/java and
  .clide/*.jar, removed afterward - none existed before)` quand rien
  n'existait avant, ou `... [OK] (imported via a temporary .project/.classpath,
  the project's own restored afterward - see .clide/tmp/ for what was
  actually used)` sinon. Vérifié sur PlantUML —
  voir « Test sur PlantUML » plus bas.
- Commandes implémentées :
  - `help` → liste toutes les commandes enregistrées (mot-clé, paramètres,
    description), généré depuis leurs annotations — voir plus bas. Affiché
    sous forme de tableau ASCII via `clide.util.TextTable`, classe générale
    (bordures `+`/`-`/`|`, colonnes alignées sur la valeur la plus large),
    sans connaissance du contenu affiché — réutilisable telle quelle par
    d'autres commandes qui voudraient un rendu tabulaire. Chaque colonne a
    une largeur maximale (`DEFAULT_MAX_COLUMN_WIDTH` = 100 par défaut,
    surchargeable au constructeur) : une cellule plus large est repliée sur
    plusieurs lignes en coupant aux espaces (word wrap) ; un mot sans espace
    pour couper (donc plus large que la colonne) reste entier sur sa ligne,
    jamais coupé au milieu. Concepts réifiés en classes dédiées
    (`clide.util`, package-privées sauf `TextTable`) : `Column` (en-tête +
    largeur max), `Cell` (texte source → lignes repliées), `Row` (une ligne
    de cellules, hauteur = max des hauteurs de ses cellules, complétée de
    lignes vides pour les cellules plus courtes).
  - `help_ai` → même contenu que `help` (mot-clé, paramètres, description,
    triées alphabétiquement) mais pour un client IA (Claude) plutôt qu'un
    humain : une ligne par commande (`keyword <param> ... - description`),
    zéro octet décoratif — pas de titre, pas de bordures, pas de wrap, pas de
    ligne de séparation. `help` (le tableau `TextTable`) et `help_ai`
    partagent la même logique de tri (`new TreeSet<>(context.getCommands())`,
    dupliquée entre les deux plutôt que factorisée — trop peu de code pour
    justifier un partage) mais divergent volontairement sur le rendu : les
    deux publics (humain / IA) ont des besoins de lisibilité opposés, pas de
    format de compromis unique.
  - `exit` → quitte proprement (arrête tous les jdtls ouverts, via un
    shutdown LSP propre avant de tuer chaque processus).
  - `print_diagnostics <all|errors>` → réaffiche les diagnostics du dernier
    build du projet (`all` : tout, `errors` : erreurs uniquement) — le build
    dont il s'agit est celui fait automatiquement au démarrage du daemon (ou
    relancé au besoin, voir plus bas).
  - `search_regex <chemin_initial> <regex_chemin> <regex_cherché>` → parcourt
    `chemin_initial`, ne garde que les fichiers dont le chemin (normalisé en
    `/`, donc portable Windows/Linux) matche `regex_chemin`, puis grep
    `regex_cherché` ligne par ligne dans ces fichiers.
  - `find_symbol <nom>` → cherche un symbole par nom dans tout le projet, sans
    connaître à l'avance le fichier/la ligne — ce qui manquait en amont des
    commandes de navigation par position (voir `TODO.md`, retiré une fois
    cette commande faite) : il fallait grepper soi-même pour trouver la ligne
    avant de pouvoir appeler `find_declaration`. Bâtie sur `workspace/symbol`
    (`JdtlsSession.findSymbol`) ; **le matching est entièrement délégué à
    jdtls** (typiquement flou/camelCase en pratique, pas une égalité stricte)
    — clide ne filtre rien lui-même en plus, par choix : `find_symbol
    UGraphic` peut très bien remonter aussi `UGraphicSvg`, `UGraphicNull`,
    etc. Chaque résultat est préfixé par la nature du symbole entre crochets
    (`[class]`, `[interface]`, `[method]`, `[field]`, `[constructor]`,
    `[enum]`, `[constant]`, `[package]`, `[variable]`, `[property]`,
    `[function]`, `[enum member]`, `[struct]`, ou `[symbol]` si le code
    `SymbolKind` LSP n'est pas reconnu), suivi du même format
    `chemin/relatif.java:ligne: contenu de la ligne` que `find_declaration`/
    `find_reference`/`find_implementation`/`hover`/`list_members` —
    volontairement, pour pouvoir recopier tel quel le fichier/la ligne d'un
    résultat dans l'une de ces commandes juste après.

    **Testé de bout en bout** (clone GitHub frais de `plantuml/clide`, jdtls
    extrait, self-test — `clide` sur lui-même) : `find_symbol JdtlsSession`
    renvoie bien `[class] src/main/java/clide/jdtls/JdtlsSession.java:34: public
    class JdtlsSession {`.
  - `find_declaration <what> <symbole>` (notation `<chemin fichier>:<ligne>:
    <nom>`, voir la section dédiée plus bas) → où est réellement définie la
    déclaration du symbole (pas juste un usage). `<what>` vaut `method` (→
    `textDocument/definition`, la déclaration du symbole lui-même) ou `type`
    (→ `textDocument/typeDefinition`, la classe/interface du type déclaré du
    symbole — pas la déclaration du symbole lui-même, et pas son type
    d'exécution : le LSP ne connaît que le type statique déclaré) ; toute
    autre valeur est rejetée avant même d'atteindre jdtls. La ligne est
    1-based (comme affichée en lisant le fichier) ; le symbole est cherché
    comme mot entier sur cette ligne (`\bsymbole\b`), clide en déduit la
    colonne — pas de comptage de caractères à faire. Affiche toutes les
    locations renvoyées (`chemin/relatif.java:ligne: contenu de la ligne`), ou
    `"<no definition found>"` si vide. Pas de `textDocument/didOpen` envoyé
    avant la requête (repose sur le modèle déjà construit par le dernier
    `build()`, fait automatiquement au démarrage du daemon).

    Remplace les anciennes `goto_definition`/`goto_type_definition` (retirées
    — voir « Harmonisation find_declaration/find_reference/find_implementation »
    plus bas) : mêmes deux requêtes LSP exactement, mais réunies sous un seul
    nom de commande avec `<what>` pour les distinguer plutôt que deux
    commandes séparées sans lien apparent dans leur nom.

    **Testé de bout en bout, clide sur lui-même** (clone GitHub frais de
    `plantuml/clide`, jdtls extrait, `ant run`) : `find_declaration method`
    sur une variable renvoie sa déclaration locale (ex. `command` dans
    `Main.java` → ligne de `final Command command = registry.find(keyword);`)
    ; `find_declaration type` sur ce même symbole renvoie directement la
    classe de son type déclaré (`public abstract class Command {`), sans
    repasser par la déclaration locale. Confirmé aussi sur
    `context`/`ClideContext`. **Confirmé au passage : aucun
    `textDocument/didOpen` préalable n'est nécessaire**, la requête aboutit
    directement sur le modèle du dernier `java/buildWorkspace` — l'incertitude
    notée plus haut est levée (confirmé aussi sur PlantUML — 3600 fichiers :
    mêmes commandes, réponses directes sur le modèle du build de démarrage).
    Cas d'erreur (symbole absent de la ligne donnée) : message clair,
    `Symbol 'foobar' not found on line 55 of ...`.
  - `find_implementation <what> <symbole>` → interroge
    `textDocument/implementation` : quelles classes/méthodes implémentent ou
    surchargent réellement le symbole visé — typiquement une méthode
    abstraite ou d'interface. C'est la question de polymorphisme posée dès
    l'origine du projet. Renommée depuis `goto_implementation` une fois
    `find_declaration`/`find_reference` en place, pour suivre le même schéma
    de nommage (voir « Harmonisation » plus bas) ; `<what>` a été ajouté par
    symétrie même si, ici, une seule requête LSP existe (pas de branchement
    comme pour `find_declaration`) — il documente quand même une vraie
    distinction conceptuelle : pointé sur un type, « qui implémente cette
    interface/étend cette classe abstraite » ; pointé sur une méthode, « qui
    la surcharge ». jdtls résout laquelle des deux s'applique à partir de la
    seule position, mais `<what>` sert de garde-fou typo côté client (même
    validation littérale que pour `find_declaration`/`find_reference`) et
    documente l'intention de l'appelant.

    **Testé de bout en bout, clide sur lui-même** (re-testé après l'ajout de
    `hover`/`list_members`/`find_reference` — la liste évolue avec le nombre
    de commandes) : `find_implementation` sur `executeCommand` (méthode
    abstraite de `Command.java`) renvoie exactement les implémentations
    concrètes existantes, sans bruit (ni la déclaration abstraite, ni les
    sites d'appel `command.executeCommand(...)` qu'un grep aurait remontés).
    `PositionCommandSupport` (voir « Harmonisation » plus bas) n'y apparaît
    plus, contrairement à l'ancienne `GotoPositionCommand` : elle n'étend
    désormais plus `Command` du tout, ce n'est donc plus une fausse
    implémentation remontée à tort.
  - `find_reference <what> <symbole>` → interroge
    `textDocument/references` : partout où `symbole` est réellement utilisé
    dans le projet, déclaration exclue — l'inverse de `find_implementation`
    (qui part d'une interface/méthode abstraite vers ses implémentations
    concrètes ; `find_reference` part de n'importe quel symbole vers tous ses
    usages réels). Envoie `includeDeclaration: false` dans le `context` de la
    requête LSP : la déclaration est déjà connue (c'est l'entrée de la
    commande), seuls les vrais usages comptent — avec `includeDeclaration:
    true`, une méthode jamais appelée remonterait quand même 1 résultat (sa
    propre déclaration), ce qui fausserait justement la réponse à « cette
    méthode est-elle vraiment appelée quelque part ? ». `<what>` est validé
    (même contrôle littéral que `find_declaration`/`find_implementation`)
    mais purement cosmétique ici : une seule requête LSP existe, donc
    `<what>` ne change jamais le comportement — il n'est là que pour la
    symétrie de nom avec `find_declaration`/`find_implementation`. Renommée
    depuis `goto_references` (voir « Harmonisation » plus bas).

    **Testé de bout en bout, clide sur lui-même** : `find_reference` sur
    `getCurrentSession` (déclarée ligne 40 de `ClideContext.java`) renvoie
    exactement ses sites d'appel réels, sans la déclaration elle-même —
    confirme que `includeDeclaration: false` fonctionne comme prévu.
  - `hover <symbole>` → signature/Javadoc que jdtls
    connaît pour ce symbole précis, à cet endroit précis (pas un autre
    emplacement comme `find_declaration`/`find_reference`/`find_implementation`
    — `hover` explique le symbole où il se trouve). Même résolution de
    position que ces commandes (mot entier sur la ligne, colonne déduite). Bâtie sur `textDocument/hover` (`JdtlsSession.hover`). Le texte
    renvoyé par jdtls (généralement du Markdown) est affiché tel quel, sans
    reformatage ; `"<no hover info>"` si jdtls n'a rien à dire (type non
    résolu — jar manquant dans `.clide` — ou hover non applicable à ce genre
    de symbole). Répond au besoin noté dans `TODO.md` (retiré une fois cette
    commande faite) : reconstituer à la main la signature d'une classe externe
    (TeaVM/OpenPDF/Ant) en grepant ses appels.
  - `list_members <symbole>` → liste les membres
    directs (méthodes, champs, constructeurs — pas les membres d'un type
    imbriqué, seulement le type lui-même en tant que membre) de la
    classe/interface/enum `symbole`, déclarée à cette ligne de ce fichier.
    Même résolution de position que `find_declaration`/`find_reference`/
    `find_implementation`/`hover`, mais ici pour désigner quel type inspecter
    plutôt qu'où sauter/quoi expliquer. Bâtie sur
    `textDocument/documentSymbol` (`JdtlsSession.listMembers`), qui a besoin
    que `hierarchicalDocumentSymbolSupport` soit déclaré dans les
    `capabilities` de `initialize` (sinon jdtls renvoie un `SymbolInformation[]`
    plat, sans `children`, et `list_members` ne trouverait jamais rien).
    Chaque résultat suit le même format `[nature] chemin/relatif.java:ligne:
    contenu de la ligne` que `find_symbol`. Erreur claire si `symbole` n'est
    pas un type (une classe/interface/enum) à cette ligne — `list_members` ne
    fonctionne que sur des types, pas des méthodes/champs. Répond au même
    besoin que `hover` (voir `TODO.md`, retiré une fois ces deux commandes
    faites), en listant directement tous les membres d'un coup plutôt qu'un à
    la fois.

    **Testé de bout en bout** (clone GitHub frais de `plantuml/clide`, jdtls
    extrait, self-test) : `hover` sur `Command` (déclaré ligne 37 de
    `Command.java`) renvoie bien le Javadoc de la classe (Markdown, avec le
    lien `Source: [clide](file:///.../Command.java#37)` que jdtls ajoute
    lui-même) ; `list_members` sur ce même `Command` renvoie exactement ses 8
    méthodes déclarées, dans l'ordre du fichier, aucun faux positif/négatif
    (la classe n'a aucun champ — cohérent avec le résultat).

  - `run_test <symbole>` → lance le test que `symbole` désigne : toute la
    classe quand `symbole` nomme la classe de test, cette seule méthode
    sinon. Prend un `ParamType.SYMBOL` plutôt qu'un nom de classe pleinement
    qualifié, pour que la réponse de `find_symbol` se recopie sans retouche —
    l'enchaînement « résultat → commande suivante » que `TESTS.md` identifie
    comme le point fort de l'outil. Voir « Lancer les tests du projet
    ouvert ».
  - `run_tests <all|failures>` → lance tous les tests du projet. `failures`
    ne liste que les tests en échec (sur une suite de taille réelle, c'est la
    seule partie lisible) ; toute autre valeur liste tout. Les totaux sont
    affichés dans les deux cas — même convention littérale que `rebuild` et
    `print_diagnostics`.

### Harmonisation find_declaration/find_reference/find_implementation (remplace goto_*)

Les quatre commandes `goto_definition`/`goto_type_definition`/
`goto_implementation`/`goto_references` avaient des noms qui ne suivaient
aucun schéma commun, et deux d'entre elles (`goto_definition`/
`goto_type_definition`) ne se distinguaient que par la question posée à
propos du même symbole — pas vraiment deux verbes différents. Renommage en
trois commandes `find_declaration`/`find_reference`/`find_implementation`,
chacune prenant un paramètre `<what>` (`method` ou `type` — vocabulaire
repris de l'IHM Eclipse, où « type » désigne classe, interface, annotation,
enum ou record indifféremment — validé par égalité littérale dans
`executeCommand()`, pas via `ParamType` : ce contrôle-là n'existe pas au
niveau du protocole comme pour `SYMBOL`/`REGEX`) :

- `find_declaration` remplace `goto_definition`/`goto_type_definition` —
  ici `<what>` sélectionne réellement une requête LSP différente
  (`textDocument/definition` vs `textDocument/typeDefinition`).
- `find_reference` remplace `goto_references` — `<what>` est purement
  cosmétique (une seule requête, `textDocument/references`, existe), gardé
  pour la symétrie de nom et comme garde-fou anti-typo.
- `find_implementation` remplace `goto_implementation` — même requête
  unique (`textDocument/implementation`) qu'avant, mais `<what>` documente
  une vraie distinction conceptuelle (type → qui l'implémente/l'étend,
  méthode → qui la surcharge) même si jdtls la résout lui-même à partir de
  la seule position.

`GotoPositionCommand`, la classe intermédiaire partagée par les quatre
anciennes commandes `goto_*`, a été réécrite en `PositionCommandSupport` :
une classe utilitaire `final`, constructeur privé, une seule méthode statique
`goToAndFormat(ClideContext, String commandName, String lspMethod, String
symbolText, Map<String,Object> requestContext)` — elle n'étend plus
`Command` du tout (elle ne l'était jamais vraiment conceptuellement : pas de
`@Keyword` propre, personne ne l'invoque comme commande). Effet de bord
positif : elle n'apparaît plus dans les résultats de `find_implementation`
sur `Command.executeCommand` — avant, `GotoPositionCommand` y figurait à
tort, comme une fausse implémentation parmi les vraies.

`goto_definition`, `goto_type_definition` et `goto_references` ont été
purement et simplement supprimées (`goto_implementation` renommée plutôt que
supprimée, voir plus haut) : leur usage revient exactement au même via
`find_declaration`/`find_reference` avec le bon `<what>`, sans perte de
capacité — vérifié en sandbox que les anciens noms renvoient bien
`?SYNTAX ERROR` (commande inconnue) après suppression, et que `help`/
`help_ai` n'en gardent aucune trace.

**Pages `man` volontairement dépourvues de détails LSP.** Les descriptions
et sections ERRORS des quatre commandes `find_*` ne mentionnent ni
`textDocument/*`, ni jdtls, ni le protocole/la validation interne — elles
disent uniquement ce que chaque commande trouve et sous quelle forme, jamais
comment elle s'y prend. Cette information technique reste dans le Javadoc de
classe (au-dessus de `public class Find*Command`), destiné à qui lit le
code, pas dans `@Manual` qui est lu par l'utilisateur de `man` — les deux
publics n'ont pas besoin des mêmes détails.

### Syntaxe des commandes : un token par ligne

Le séparateur entre le mot-clé d'une commande et ses paramètres — et entre
les paramètres eux-mêmes — n'est **pas l'espace mais le retour chariot** :
chaque token (mot-clé, puis chaque paramètre) occupe sa propre ligne. Ça évite
d'avoir à échapper quoi que ce soit dans un chemin ou un regex qui contient
des espaces.

Au lieu de :
```
find_symbol JdtlsSession
search_regex /src \*.java foo\w+
print_diagnostics all
```

on écrit :
```
find_symbol
JdtlsSession
search_regex
/src
\*.java
foo\w+
print_diagnostics
all
```

### Notation `<chemin fichier>:<ligne>:<nom>` (paramètre unique, remplace le triplet)

`find_declaration`, `find_reference`, `find_implementation`, `hover` et
`list_members` prenaient chacune trois
paramètres séparés (`<chemin fichier>`, `<ligne>`, `<symbole>`), demandés sur
trois échanges distincts — répétitif côté client, et rien ne garantissait avant
l'exécution que le triplet désignait bien quelque chose de réel. Remplacé par
un paramètre unique `<symbole>`, écrit `<chemin fichier>:<ligne>:<nom>` (ex.
`src/main/java/clide/command/ManualCommand.java:27:needsJdtlsSession`) — un
seul échange au lieu de trois, et la notation détermine forcément un symbole
plutôt que trois valeurs à corréler soi-même côté client. `<chemin fichier>`
est **toujours relatif au projet ouvert** (`ClideContext.getProjectRoot()`),
jamais au répertoire courant du process daemon — qui n'a de toute façon
aucune signification stable pour le client (le daemon est lancé en
arrière-plan par `ClideClient`, voir plus haut).

Réifié en une classe `clide.core.Symbol` (chemin absolu, ligne 1-based, nom,
colonne 0-based déjà résolue) : `Symbol.parse(token, projectRoot)` est
l'unique point de construction — tout `Symbol` obtenu a donc forcément déjà
été validé (fichier existant, ligne dans les bornes, nom présent comme mot
entier `\bnom\b` sur cette ligne). Approche objet, comme demandé : `Symbol`
porte `retrieveJavadoc(JdtlsSession)`, un simple appel à `session.hover(this)`
(voir `HoverCommand`) plutôt que de manipuler le triplet à la main à chaque
site d'appel. `JdtlsSession.goToPosition`/`hover`/`listMembers` prennent
désormais un `Symbol` directement — `wholeWordColumn`/`findWholeWordColumn`
ont disparu de `JdtlsSession`, cette logique de résolution vit désormais
uniquement dans `Symbol`.

`ParamType.SYMBOL` (déjà présent dans l'énum, jusqu'ici jamais relié à une
vérification) est ce qui déclenche ce contrôle de surface **avant même que la
commande ne parte en exécution** : `ClideDaemon.readParams()` lit d'abord les
N lignes attendues, puis un nouveau `ClideDaemon.validate()` relit chaque
valeur selon son `ParamType` (nouveau `Command.getParamTypes()`, miroir de
`getDescriptionParam()`) — pour `SYMBOL`, ça revient à appeler `Symbol.parse()`
et à renvoyer directement son message d'erreur si ça échoue. Aucun appel jdtls
n'a lieu tant que cette étape n'a pas réussi : une commande sur un fichier
inexistant ou une ligne hors bornes échoue immédiatement (`?SYNTAX ERROR:
...`), sans jamais atteindre `ensureSessionReady()`/`executeCommand()`.

Même principe pour `ParamType.REGEX`, désormais réellement utilisé par
`search_regex` (`<chemin regex>`/`<contenu regex>`, jusqu'ici déclarés en
`SINGLE_LINE`) : la validation compile le texte via
`java.util.regex.Pattern.compile()` et renvoie l'erreur de syntaxe telle
quelle si ça échoue, avant même que `ResearchRegexCommand` ne soit invoquée
(le `try`/`catch` qu'elle fait elle-même autour du même `Pattern.compile()`
reste en place par défense en profondeur, mais devient redondant en
pratique).

### Paramètres multi-lignes (`ParamType.MULTI_LINE`)

Un corps de méthode Java (ou tout autre bloc de code qu'un client veut
envoyer comme un seul paramètre) est par nature multi-ligne — la contrainte
« un token par ligne » ci-dessus ne suffit plus : contrairement aux autres
`ParamType`, il n'y a pas de nombre de lignes à annoncer à l'avance, le
client lui-même ne sait pas forcément combien de lignes il va taper avant de
commencer. `ParamType.TEXT_BLOCK` est donc renommé `ParamType.MULTI_LINE`
(miroir de `SINGLE_LINE`, plus explicite que l'ancien nom) et
`ClideDaemon.readParams()` le lit désormais en deux temps au lieu d'un :

1. Une première ligne, le **terminateur** — une chaîne discriminante au
   choix du client, jamais validée ni interprétée, juste peu susceptible
   d'apparaître telle quelle comme ligne du contenu réel.
2. Puis autant de lignes que le client en envoie, conservées telles quelles
   (**sans `trim()`** — contrairement à tous les autres `ParamType` :
   l'indentation fait partie de la valeur, par exemple un corps de méthode
   indenté à la tabulation), jusqu'à rencontrer une ligne strictement égale
   au terminateur. Cette ligne est consommée mais exclue du résultat ; les
   lignes précédentes sont jointes par `"\n"` (un bloc vide — terminateur
   dès la première ligne — donne `""`).

Réifié en deux méthodes privées dans `ClideDaemon` : `readSingleLineParam()`
(l'ancien comportement, une ligne lue puis `trim()`ée) et
`readMultiLineParam()` (le protocole ci-dessus) ; `readParams()` choisit
entre les deux selon le `ParamType` de chaque paramètre
(`command.getParamTypes()`). Au passage, `readParams()` renvoie désormais
réellement `null` — comme sa Javadoc l'a toujours prétendu — dès qu'un
paramètre (ou, pour `MULTI_LINE`, son terminateur, ou le bloc lui-même)
rencontre l'EOF avant d'être complètement lu ; auparavant l'implémentation
substituait silencieusement une chaîne vide à la place, rendant ce `null`
mort côté appelant (`runSession()` le testait déjà, mais ne le recevait
jamais).

**Testé** via un test dédié, hors du dépôt (`readParams()` et ses deux
sous-méthodes sont `private`, invoquées par réflexion sur une `Command`
factice) : paramètres `SINGLE_LINE`/`MULTI_LINE` mélangés dans une même
commande, bloc vide, indentation à la tabulation et lignes vides préservées
telles quelles, `trim()` toujours appliqué aux paramètres `SINGLE_LINE`, et
EOF prématurée — pendant le terminateur ou pendant le bloc — renvoyant bien
`null`. Aucune commande ne déclare encore de paramètre `MULTI_LINE` à ce
jour ; ce protocole est prêt à être utilisé par une future commande
d'édition (voir TODO).

**`<chemin fichier>` accepte deux formes**, départagées par
`Symbol.resolveFile()`/`isFileUri()` : un chemin relatif classique (résolu
contre `projectRoot`, comme décrit plus haut), ou une URI `file:` telle
quelle — c'est le format dans lequel `find_symbol`/`find_declaration`/
`find_reference`/`find_implementation`/`hover`/`list_members` impriment
déjà chacun de leurs résultats (voir `JdtlsSession.formatLocation()`), donc
un résultat recopié tel quel depuis l'un de ces retours et renvoyé en
paramètre d'un `hover`/`find_declaration` suivant fonctionne sans avoir à le
retoucher à la main. `projectRoot.resolve()` seul
ne suffit pas pour une URI : sous Windows, `"file:///C:/..."` n'est pas un
chemin Windows valide (le `:` après la lettre de lecteur — ou après `file` —
fait échouer le parseur de chemin Windows, `InvalidPathException: Illegal
char <:> at index ...`), d'où le passage par `java.net.URI`/`Paths.get(URI)`
plutôt que par `Path.resolve()` dès qu'un `pathArgument` commence par `file:`
(insensible à la casse).

**Testé de bout en bout** (clone GitHub frais, jdtls extrait de l'archive
commitée, build Ant, `clide` sur lui-même) : `hover`, `find_implementation` et
`list_members` avec la nouvelle notation à paramètre unique (ex.
`src/main/java/clide/core/Command.java:56:needsJdtlsSession`) renvoient
exactement les mêmes résultats qu'avant ce refactor (respectivement le
Javadoc de la méthode, ses 6 implémentations concrètes réelles, et les 10
membres de `Command` — `getParamTypes()` inclus, la nouvelle méthode) ;
`find_declaration` sur un chemin inexistant échoue en `?SYNTAX ERROR: Not a
file: ...` et sur un symbole absent de la ligne donnée en `?SYNTAX ERROR:
Symbol '...' not found on line ...`, dans les deux cas sans qu'aucune requête
LSP ne parte ; `search_regex` avec un `<chemin regex>` syntaxiquement
invalide (`[invalid(`) échoue en `?SYNTAX ERROR: Invalid regex '...'` avant
même que `search_regex` ne commence à parcourir les fichiers. Testé
séparément (sandbox Linux, projet buildé et servi par un vrai daemon jdtls) :
`hover` avec `<symbole>` sous forme d'URI `file:///.../ClideContext.java:29:
ClideContext` et sous forme de chemin relatif
`src/main/java/clide/core/ClideContext.java:29:ClideContext` renvoient tous
les deux, à l'identique, le Javadoc de `ClideContext` — plus de `?SYNTAX
ERROR: Illegal char <:> at index ...` sur la forme URI. Un `hover` sur une
URI pointant vers un fichier inexistant échoue toujours proprement en
`?SYNTAX ERROR: Not a file: ...`.

### Transactions (`open_transaction`/`commit_transaction`/`rollback_transaction`/`diff_transaction`/`restore_file`)

Toute modification de fichier doit désormais se faire à l'intérieur d'une
**transaction** : `clide` garde une copie de sauvegarde de chaque fichier
touché, ce qui permet d'annuler proprement (`rollback_transaction`) si une
modification tourne mal, ou de consulter/annuler un seul fichier
(`diff_transaction`/`restore_file`) sans tout annuler.

```
open_transaction
$refactor_foo
```

ouvre la transaction `$refactor_foo` — un id commence forcément par `$`,
suivi de caractères `\w` en minuscule (`TransactionStack.ID_PATTERN`,
nouveau `ParamType.TRANSACTION_ID`, vérifié en surface par
`ClideDaemon.validate()` avant même que `open_transaction` ne s'exécute,
même principe que `REGEX`/`SYMBOL`). Aucune commande de modification de
fichier ne peut s'exécuter tant qu'aucune transaction n'est ouverte —
nouveau `Command.needsOpenTransaction()` (par défaut `false`), vérifié par
`ClideDaemon.runSession()` juste avant `needsJdtlsSession()`/
`executeCommand()`. Aucune commande n'existe encore aujourd'hui pour
modifier un fichier (ce sera l'objet d'un prochain point) ; ce protocole est
prêt à être utilisé par la première d'entre elles, via
`context.getTransactions().backupBeforeModification(fichierAbsolu)` — à
appeler juste avant d'écrire quoi que ce soit sur disque (et à faire
précéder d'un `needsOpenTransaction()` à `true` sur la commande elle-même).

Ensuite : `commit_transaction $refactor_foo` garde les modifications,
`rollback_transaction $refactor_foo` les annule toutes, `diff_transaction
$refactor_foo` liste les fichiers modifiés (deuxième paramètre `<chemin>`
vide — convention identique à `print_diagnostics <all|errors>`, le
framework `Command` n'a pas de paramètre optionnel) ou, `<chemin>` donné,
affiche un diff unifié de ce fichier ; `restore_file $refactor_foo
src/foo.java` ne restaure que ce fichier-là, sans toucher au reste ni
fermer la transaction.

**Sous-transactions imbriquées** : une fois `$refactor_foo` ouverte,
`open_transaction $refactor_foo$part1` ouvre une sous-transaction, puis
`$refactor_foo$part1$a` sous celle-ci, etc. `commit_transaction
$refactor_foo` alors que `$part1` est encore ouverte la commit d'abord
implicitement (la plus profonde d'abord), en fusionnant ses sauvegardes vers
sa transaction parente ; `rollback_transaction $refactor_foo` annule dans
l'autre sens (la plus récente d'abord), pour que la sauvegarde la plus
ancienne (celle de `$refactor_foo` lui-même) soit celle qui gagne à la fin —
c'est elle qui reflète le véritable état d'avant toute modification du
sous-arbre.

**Déviation volontaire par rapport à la spec littérale** : `TransactionStack`
(demandé sous le nom `TransactionsStack`) est réifiée en une vraie **pile**
(LIFO), pas un arbre à branches : on ne peut ouvrir qu'une sous-transaction
de celle actuellement au sommet de la pile — deux sous-transactions sœurs
(`$refactor_foo$part1` et `$refactor_foo$part2` ouvertes simultanément) sont
refusées tant que la première n'est pas commitée/annulée. Ce choix colle au
nom demandé pour la classe et lève une ambiguïté que la spec ne tranchait
pas : quelle transaction reçoit la sauvegarde d'une modification quand
plusieurs branches pourraient être ouvertes à la fois. Autres déviations,
toutes documentées dans le Javadoc des classes concernées :

- Répertoires **imbriqués**, pas plats : `.clide/transactions/refactor_foo/part1`
  (spec littérale : `.clide/transactions/part1`) — évite toute collision
  entre deux transactions de même nom de segment ouvertes sous des parents
  différents (impossible en pratique avec la pile stricte ci-dessus, mais
  plus simple et plus sûr que de s'appuyer sur cette invariant).
- Marqueur de fichier créé : un fichier `created.txt` (une ligne par chemin
  relatif) plutôt que le fichier « vide » de la spec littérale — un fichier
  vide ne se distinguerait pas d'un vrai fichier vide sauvegardé tel quel.
- **Premier backup gagne**, au sein d'une transaction *et* lors de la fusion
  d'une sous-transaction vers son parent (`Transaction.mergeInto()`) : la
  sauvegarde la plus ancienne d'un fichier donné est toujours celle qui
  survit, c'est elle qui représente l'état juste avant que ce sous-arbre de
  transactions ne commence à toucher au fichier.
- `restore_file` ne modifie pas la comptabilité de la transaction (la
  sauvegarde reste en place) : rappelable plusieurs fois, et
  `diff_transaction`/`modifiedFiles()` continuent de lister le fichier comme
  modifié après un `restore_file` dessus.

**Garde-fou au démarrage du daemon** : si le process plante en cours de
transaction, `.clide/transactions/` reste dans un état instable (comme prévu
par la demande initiale). `ClideDaemon.run()` refuse donc de démarrer
(`TransactionStack.refuseIfDirty()`, nouvelle étape `(1/4)`, avant même
l'initialisation de jdtls) si ce répertoire existe et n'est pas vide — au
lieu de deviner comment reprendre un état dont il ne connaît ni l'ordre
d'ouverture ni la pile exacte, il laisse le nettoyage à l'utilisateur,
comme demandé. `.clide/` est ajouté au `.gitignore` (nouveau, ce répertoire
n'existait pas avant ce chantier — seul `.clide.lock`, un fichier, existait
déjà à la racine).

Réifié en deux classes `clide.core` : `Transaction` (un seul niveau —
sauvegarde/restauration/fusion pour *son* répertoire propre, jamais appelée
directement par une commande) et `TransactionStack` (discipline de pile,
parsing des ids, cascade de commit/rollback, `refuseIfDirty()` — c'est elle
que `ClideContext.getTransactions()` expose, seul point d'entrée utilisé par
les commandes). Diff unifié rendu par `clide.util.UnifiedDiff`, LCS
classique (programmation dynamique), zéro dépendance externe, même
cohérence de style que `Json`/`LspClient` (voir plus bas).

**Testé** via deux suites de tests dédiées, hors du dépôt (pas de framework
de test dans le projet — même approche que pour `MULTI_LINE`) : 14 cas sur
`TransactionStack`/`UnifiedDiff` (commit/rollback simples, cascade imbriquée
avec premier-gagne à la fusion, cascade de rollback la plus profonde
d'abord, refus d'une sous-transaction sœur, refus d'une sous-transaction
sans parent ouvert, validation de l'id, `restore_file` unitaire sans
fermeture de transaction, fichier créé — rollback le supprime, commit le
garde —, commit à mi-chaîne avec fusion vers le vrai parent, `refuseIfDirty`
dans ses trois états, rendu `UnifiedDiff` basique et sur fichier créé) et 3
cas sur les 5 commandes elles-mêmes via `ClideContext`/`Command` (métadonnées
@Keyword/@Param/@Help/@Manual, flux complet
open→backup→diff→restore→commit→rollback, erreurs `CommandResult.error`
sur id inconnu ou sous-transaction sœur refusée). Tout compilé et exécuté
dans un sandbox Linux avec un miroir complet du projet réel (JDK 21).

**`exit`/`quit` vs `terminate` : une transaction ouverte laissée en plan.**
Comme `ClideContext` (et donc `TransactionStack`) vit pour toute la durée du
daemon et pas par connexion, une transaction ouverte par un client puis
laissée telle quelle (simple déconnexion, pas forcément `exit`/`quit`) reste
ouverte sur la pile pour la connexion suivante — potentiellement un client
tout à fait différent, qui se retrouverait bloqué par un `open_transaction`
refusé sans savoir pourquoi. Deux cas bien distincts :

- `exit`/`quit` (`DisconnectCommand`) ne touchent jamais `TransactionStack` :
  toute l'idée de laisser le daemon tourner est justement de pouvoir
  reprendre plus tard là où on s'est arrêté, transaction ouverte comprise —
  les fermer d'autorité casserait ce cas d'usage normal. `openIds()` (nouveau
  sur `TransactionStack`) permet juste d'afficher un avertissement
  informatif (« Warning: transaction(s) still open … ») si la pile n'est
  pas vide au moment de la déconnexion — rien n'est bloqué.
- `terminate` (`TerminateCommand`), lui, refuse purement et simplement de
  s'exécuter si `openIds()` n'est pas vide (`CommandResult.error`, aucun
  effet de bord). Contrairement à `exit`/`quit`, `terminate` met fin au
  process du daemon pour de bon — une transaction dont plus personne ne
  viendra jamais faire `commit_transaction`/`rollback_transaction` est
  exactement l'état sale que `refuseIfDirty()` est censé détecter au
  prochain démarrage (voir plus haut), réservé à un vrai plantage — pas à
  un arrêt volontaire. Ce refus maintient l'invariant « `terminate` ne
  laisse jamais `.clide/transactions` sale », ce qui rend `refuseIfDirty()`
  au démarrage suivant un signal fiable de plantage réel, jamais un faux
  positif dû à un arrêt propre mais pressé.

**Testé** (4 cas supplémentaires, même sandbox) : `exit`/`quit` réussissent
toujours et n'altèrent pas la pile, avec avertissement listant les ids
ouverts si la pile n'est pas vide, silencieux sinon ; `terminate` refuse
proprement (sans toucher `isShutdownRequested()` ni la pile) tant qu'une
transaction est ouverte, et réussit normalement une fois la pile vide.

### Architecture des commandes (pattern Command)

Le dispatch dans `Main.java` est générique : `Main` ne connaît aucune
commande par son nom, il lit un mot-clé puis délègue à l'implémentation
correspondante. Tout vit dans `clide.core` :

- **`Command`** — classe abstraite de base. `executeCommand(ClideContext,
  String...)` fait le travail. Le mot-clé, la description et les paramètres
  attendus sont portés par des annotations sur le constructeur sans argument
  (`@Keyword`, `@Help`, `@Param` répétable via `@Params`), lues par
  réflexion — le constructeur sert uniquement de porte-annotations, il n'est
  jamais invoqué pour lire ces métadonnées.
- **`CommandResult`** / **`CommandStatus`** — une commande renvoie un
  résultat (`OK`/`ERROR` + message texte) plutôt que d'écrire directement
  sur la sortie standard ; c'est `Main` qui affiche.
- **`ClideContext`** — état partagé entre les commandes le temps d'une
  exécution de `clide` : les sessions jdtls ouvertes (une par projet), le
  projet courant, et si le shell doit s'arrêter.
- **`CommandRegistry`** — construit une fois au démarrage la table
  mot-clé → `Command` à partir de la liste fixe déclarée dans `Main`.
- Implémentations concrètes : `HelpCommand`, `ExitCommand`,
  `OpenProjectCommand`, `PrintDiagnosticsCommand`, `ResearchRegex`
  (`search_regex`), `FindDeclarationCommand`, `FindReferenceCommand`,
  `FindImplementationCommand` (les trois délèguent le gros du travail à
  `PositionCommandSupport.goToAndFormat()`, une classe utilitaire statique —
  pas une sous-classe de `Command` — plutôt qu'à la classe intermédiaire
  `GotoPositionCommand` d'avant le renommage, voir « Harmonisation » plus
  haut).

Ajouter une commande = ajouter une classe `clide.core` qui étend `Command`
et l'enregistrer dans `Main.commands` ; aucune autre modification de `Main`
n'est nécessaire.

Build :
```
./gradlew run
```
puis taper `help` au prompt.

### Tests unitaires de clide (`ant test`)

À ne pas confondre avec `run_test`/`run_tests`, qui lancent les tests **du
projet ouvert par clide**. Ici il s'agit des tests de clide lui-même.

- Sources dans `src/test/java`, JUnit 5 (Jupiter 5.10.1). Les jars sont
  **commités dans `lib/`** plutôt que téléchargés : Maven Central n'est pas
  accessible depuis la sandbox Claude (voir « Contrainte réseau »), et le
  principe est le même que pour l'archive jdtls — ce qui est commité est
  disponible partout. `scripts/fetch_junit.py` les récupère ou les rafraîchit
  (bibliothèque standard seule, empreinte SHA-1 vérifiée, idempotent).
- `ant test` compile puis exécute tout ; `ant test -Dtest=<FQCN>` une seule
  classe. `ant help` documente les cibles et les options.
- Le lanceur est `junit-platform-console-standalone`, présent dans `lib/`. Il
  repackage toute la plateforme JUnit, donc il est volontairement **exclu** du
  classpath de compilation des tests (sinon chaque classe JUnit s'y trouverait
  en double, avec deux versions en concurrence) et il est le **seul** jar
  JUnit du classpath d'exécution.
- Un piège d'encodage, spécifique à Ant : Ant re-journalise la sortie du JVM
  qu'il forke à travers son propre `PrintStream`, construit sur le charset du
  JVM d'Ant. Un enfant en UTF-8 sort donc quand même en `?` si Ant lui-même
  n'a pas démarré en UTF-8. D'où un thème ASCII par défaut, et
  `ANT_OPTS=-Dstdout.encoding=UTF-8 ant test -Dtest.theme=unicode` pour le
  rendu complet.

Deux conventions se sont dégagées en écrivant ces tests, et elles valent
d'être suivies :

**Un oracle externe plutôt que le comportement observé.** Les attendus de
`UnifiedDiffTest` sont la sortie de `diff -u` de GNU diffutils sur les mêmes
entrées, relevée et collée telle quelle. `UnifiedDiff` s'est révélé identique
octet pour octet, cas limites du format compris (`@@ -0,0`, fusion de deux
hunks séparés par exactement `2*CONTEXT` lignes). Ces tests ne gravent donc
pas des conventions maison : un écart futur est un bug, pas un choix.

**Vérifier que les tests servent à quelque chose.** « Ils passent » ne dit
rien. On injecte des bugs délibérés dans le code testé et on regarde si la
suite les attrape. C'est ce qui a démasqué un test de `TestSelector` qui
passait pour une mauvaise raison : il vérifiait qu'un en-tête de licence ne se
fait pas passer pour la déclaration `package`, mais les lignes d'un en-tête
commencent par ` * `, que l'ancrage `^\s*package` écarte déjà — le mécanisme
qu'on croyait tester (l'effacement des commentaires) pouvait être supprimé
sans faire échouer quoi que ce soit. Le seul cas qui trompe réellement
l'expression régulière est un `package` commenté **en colonne 0**, et c'est
lui qu'il fallait écrire.

### Le `.classpath` généré et les dossiers de test

`JdtlsSession.buildDotClasspath()` marque les dossiers de test `test="true"`
et leur donne leur propre sortie, comme le ferait `gradlew eclipse` :

```xml
<classpathentry kind="src" path="src/main/java"/>
<classpathentry kind="src" output="bin/test" path="src/test/java">
    <attributes>
        <attribute name="test" value="true"/>
    </attributes>
</classpathentry>
<classpathentry kind="output" path="bin/main"/>
```

Sans cet attribut, JDT traite le code de test comme du code de production, et
trois choses cassent plus loin : `java.project.isTestFile()` répond `false`
sur un fichier qui en est manifestement un, `java.project.getClasspaths()`
renvoie strictement la même chose pour les scopes `test` et `runtime`, et tous
les `.class` atterrissent dans un dossier unique sans moyen de distinguer les
tests du reste. Mesuré avant/après sur un projet cible :

| | avant | après |
|---|---|---|
| `isTestFile` sur un test | `false` | `true` |
| scope `test` | `bin` + jars | `bin/test` + `bin/main` + jars |
| scope `runtime` | identique au précédent | `bin/main` + jars |

**Le code de production n'a pas d'attribut `output=`** : il va dans la sortie
par défaut, déclarée `bin/main`. Nommer un troisième dossier (`bin/default`,
comme le fait Gradle) déclare une sortie où rien n'est jamais écrit — et
`getClasspaths()` rapporte alors un dossier de sortie jamais créé comme un
chemin de workspace Eclipse (`/projet/bin/default`) au lieu d'un chemin de
fichier, soit une entrée bidon à filtrer dans chaque classpath pour toujours.
Constaté en sondant, pas déduit en lisant.

Les jars de `.clide/` restent non marqués, donc visibles aussi depuis le code
de production : rien ici ne sait distinguer une dépendance de test d'une vraie,
et se tromper dans ce sens fait juste rater le signalement d'un import
douteux, alors que se tromper dans l'autre casserait une compilation qui
marchait.

**`ensureDotFilesPresent()` a disparu, remplacée par `EclipseProjectFiles`** —
voir la section suivante : ce n'est plus vrai qu'un `.classpath` déjà là
survit tel quel d'un démarrage à l'autre, et l'avertissement ci-dessus (« il
faut le supprimer pour obtenir le nouveau ») ne s'applique plus.

### `.project`/`.classpath` : ne jamais toucher aux fichiers du projet (`EclipseProjectFiles`)

Avant ce chantier, `ensureDotFilesPresent()` n'écrivait `.project`/`.classpath`
que s'ils manquaient, et les laissait sinon strictement intacts — pratique en
apparence, mais avec deux défauts. D'abord, un `.classpath` généré une fois
restait ensuite pour toujours : rien ne le régénérait quand un jar était
ajouté dans `.clide/`, il fallait le supprimer à la main (voir la section
précédente) et relancer le daemon pour que le nouveau jar soit vu. Ensuite et
surtout, un `.classpath` déjà présent — fait main, produit par
`gradlew eclipse`, ou simplement d'une autre origine — était utilisé tel
quel : correct pour ne pas l'écraser, mais ça voulait dire que le vrai
descripteur du projet ne contenait ni les dossiers de test marqués
`test="true"` ni les jars de `.clide/`, puisque ce sont précisément les deux
choses que `buildDotClasspath()` ajoute.

**Le principe retenu** : clide n'utilise plus jamais le `.project`/`.classpath`
du projet pour l'import jdtls, qu'il existe ou non — il écrit systématiquement
les siens. Mais il ne les laisse jamais traîner sur disque une fois le projet
importé : le fichier d'origine (s'il y en avait un) est restauré identique une
fois le build initial terminé ; s'il n'y en avait pas, le fichier généré par
clide est supprimé. Dans les deux cas, un `git status` sur le projet ouvert
ne voit jamais passer ni modification ni fichier nouveau à sa racine à cause
de clide.

**Mécanique (`clide.jdtls.EclipseProjectFiles`)**, appelée depuis
`JdtlsSession.start()`/`restoreEclipseFiles()` :

1. `stage(projectXml, classpathXml)`, appelée avant le handshake LSP : déplace
   `.project` et `.classpath` (s'ils existent) vers `.clide/tmp/`, dossier créé
   au besoin, puis écrit le contenu que clide vient de construire à leur
   place. Une copie de ce contenu est aussi écrite dans
   `.clide/tmp/.project.clide`/`.clide/tmp/.classpath.clide` — jamais relue
   par la suite, seulement là pour inspecter après coup ce que jdtls a
   réellement importé.
2. jdtls importe et build le projet avec les fichiers de clide en place.
3. `unstage()`, appelée seulement une fois ce build initial terminé (pas juste
   après `initialize`/`initialized` — voir plus bas pourquoi) : remet en place
   le fichier original s'il y en avait un (un seul déplacement atomique,
   remplaçant directement le fichier de clide plutôt que le supprimer puis
   replacer l'original en deux étapes séparées — un crash pile entre les deux
   ne doit jamais laisser aucun des deux fichiers en place), ou supprime
   simplement le fichier de clide sinon.

**Pourquoi attendre la fin du build, pas seulement la fin du handshake LSP.**
L'import jdtls est asynchrone (c'est pour ça que `waitForServiceReady` existe,
avec son délai faute de signal fiable — voir plus bas) : remettre le fichier
original en place trop tôt risquerait une course contre jdtls encore en train
de le lire. `start()` place donc le staging, mais c'est
`ClideDaemon.run()`/`ensureSessionReady()` qui appellent `restoreEclipseFiles()`
juste après `session.build()` (la construction initiale, dans un
`try`/`finally` commun aux deux appels : `restoreEclipseFiles()` doit tourner
que `build()` réussisse ou lève, sinon un daemon qui échoue à démarrer
laisserait le fichier original coincé dans `.clide/tmp/`).

**Vérifié empiriquement que ceci est sûr** : `.classpath` modifié sur disque
(jars retirés) sous un daemon PlantUML déjà démarré et stable — ni une
attente passive (`print_diagnostics`), ni même un `rebuild` explicite (une
vraie recompilation, 12,5 s) n'ont changé quoi que ce soit au classpath
réellement utilisé par jdtls (`run_test` continuait de résoudre les mêmes
jars). jdtls ne relit donc jamais ces fichiers une fois le projet importé —
ni passivement (pas de file watcher qui réagit), ni sur demande explicite de
rebuild. Remettre le fichier d'origine en place juste après le build initial
est donc sans risque : plus rien ne le surveille.

**Garde anti-crash, même principe que `TransactionStack.refuseIfDirty()`** :
si le daemon plante entre `stage()` et `unstage()`, le fichier original reste
coincé dans `.clide/tmp/` au lieu d'être à la racine. `EclipseProjectFiles.
refuseIfDirty()`, appelée à l'étape `(1/4)` du démarrage juste après celle de
`TransactionStack`, refuse de démarrer si `.clide/tmp/.project` ou
`.clide/tmp/.classpath` existe déjà — jamais les copies `.clide` de debug,
qui elles sont censées survivre d'un démarrage à l'autre. Deviner comment
remettre un fichier stocké dans cet état risquerait de le perdre si la
supposition est fausse ; le nettoyage reste à la charge de l'utilisateur,
comme pour les transactions.

**Conséquence pratique** : un jar ajouté dans `.clide/` est maintenant pris en
compte dès le prochain démarrage du daemon, sans plus jamais avoir à supprimer
`.classpath` à la main au préalable — `stage()` régénère systématiquement son
propre contenu à chaque démarrage.

**Testé** : suite dédiée `EclipseProjectFilesTest` (répertoires temporaires
réels, aucun montage jdtls nécessaire — toute la logique est pure système de
fichiers) — aller-retour identique à l'octet près sur un `.project`/
`.classpath` préexistant, aucune trace laissée quand rien n'existait avant,
copies de debug toujours écrites et jamais relues, `stage()` deux fois de
suite sans `unstage()` refusé, `unstage()` sans `stage()` préalable
inoffensif, `refuseIfDirty()` qui distingue un fichier original coincé (lève)
d'une simple copie de debug qui traîne (ne lève pas). Vérifié qu'un bug
injecté délibérément (`unstage()` qui ne restaure plus jamais l'original)
fait bien échouer deux tests sur huit — les deux qui portent justement sur la
restauration. Rejoué ensuite de bout en bout sur PlantUML (branche `clide`) :
cas sans fichiers préexistants (message « removed afterward - none existed
before », rien à la racine après coup, copies de debug seules dans
`.clide/tmp/`) et cas avec un `.project`/`.classpath` fait main plantés
exprès (restaurés identiques à l'octet près, `sha256sum` à l'appui, alors que
`run_test` tournait bien avec le classpath de clide entre-temps) ; garde
anti-crash déclenchée pour de vrai en laissant un `.clide/tmp/.project`
résiduel avant un démarrage - refus propre avec un message explicite, daemon
non démarré.

**Suite** : `.clide.lock` (`DaemonLock`) et `.clide-daemon.log` (`ClideClient`)
vivaient jusque-là à la racine du projet — seuls fichiers que clide y laissait
en dehors du séjour temporaire de `.project`/`.classpath` ci-dessus. Les deux
ont été déplacés dans `.clide/tmp/`, pour la même raison : ne rien laisser à la
racine qui n'y était pas déjà. `DaemonLock.file()` et `ClideClient.
ensureDaemon()` résolvent maintenant leur chemin via
`EclipseProjectFiles.stagingDir()`/`STAGING_DIR` (rendues publiques) plutôt que
de recoder chacun leur propre `".clide/tmp"` — une seule définition partagée.
`DaemonLock.write()` crée le répertoire au besoin (`Files.createDirectories`)
avant d'écrire, normalement déjà là à ce stade (`stage()` l'a créé en premier)
mais sans en dépendre ; `ClideClient.ensureDaemon()` fait de même avant
d'ouvrir le fichier de log en `Redirect.appendTo()`, puisque c'est la première
écriture dans ce répertoire côté client (avant même qu'un daemon existe pour
appeler `stage()`). Vérifié de bout en bout sur PlantUML (branche `clide`,
sans `.project`/`.classpath` préexistants) : `.clide.lock`/`.clide-daemon.log`
apparaissent bien dans `.clide/tmp/` pendant la session, rien à la racine ;
après `terminate`, `.clide.lock` est supprimé (comme avant, juste à son nouvel
emplacement) et `.clide-daemon.log` reste sur place pour inspection, comme
`.project.clide`/`.classpath.clide`.

### JUnit pour un projet cible qui n'en a aucun (`.clide/tmp/jar-junit/`, `JunitVendorJars`)

**Le trou** : `clide.jar` embarque toute la plateforme JUnit (voir « Lancer
les tests du projet ouvert » plus bas) mais uniquement sur le classpath de la
JVM qu'il forke pour *exécuter* un test — jamais sur celui que jdtls utilise
pour *compiler* le projet cible (`detectJarLibs()`, qui ne lit que
`.clide/*.jar`). Un projet sans le moindre jar JUnit dans son propre
`.clide/` échoue donc à compiler ses sources de test sous jdtls, et
`run_test` rapporte à tort « aucun test trouvé » au lieu de la vraie cause.
Constaté sur la branche `clide` de PlantUML (dépouillée de tout jar JUnit,
justement pour forcer ce cas) : 6058 erreurs de compilation avant correctif.

**Le correctif ne demande aucun commit au projet cible.** Copier des jars
JUnit dans le `.clide/` de chaque projet ouvert marcherait, mais un projet
qui commit son `.clide/` (comme PlantUML, précisément pour que les jars que
le sandbox de clide ne peut pas aller chercher sur Maven Central soient
versionnés) devrait alors aussi committer le JUnit de clide lui-même — ce que
ce mécanisme évite.

**`clide.jdtls.JunitVendorJars`** (nouvelle classe, appelée depuis
`JdtlsSession.start()` juste avant `EclipseProjectFiles.stage(...)`, donc
avant que `buildDotClasspath()`/`detectJarLibs()` ne lisent quoi que ce
soit) : extrait trois jars — `junit-platform-console-standalone-1.10.1.jar`
(plateforme JUnit 5 complète, autoportante — Jupiter API/moteur/params,
platform-commons, opentest4j, apiguardian, tout sous leurs vrais noms de
paquet, pas de shading), `junit-pioneer-2.3.0.jar`, `xmlunit-core-2.12.0.jar`
(les deux extras que clide vend déjà pour ses propres tests) — vers
`.clide/tmp/jar-junit/` dans le projet cible, de façon idempotente (un jar
déjà présent n'est jamais retéléchargé/réécrit). `detectJarLibs()` liste
d'abord les jars du `.clide/` du projet, puis ceux de `.clide/tmp/jar-junit/`
— le choix du projet cible gagne toujours si un JUnit y est déjà présent,
clide ne fait que combler ce qui manquerait sinon.

**Où vivent ces jars dans `clide.jar` lui-même** : embarqués tels quels (non
explosés) sous `resource/vendor-junit/` — même répertoire de premier niveau
`resource/` que `jdt-language-server-latest.zip` (voir plus haut), dans son
propre sous-répertoire pour ne jamais collisionner avec lui. Patternset
`junit.vendor.jars` dans `build.xml`, à garder synchronisé à la main avec
`JunitVendorJars.VENDORED_JAR_NAMES` — seul `ant dist` (le jar complet, pas
`ant compile`/`ant run`) les embarque, donc `ant run` reste incapable de
combler ce trou lui-même (les ressources n'existent que dans le jar
empaqueté).

**`.clide/tmp/` déjà hors de portée de git** grâce au `.gitignore` (`*`)
qu'`EclipseProjectFiles`/`DaemonLock`/`ClideClient` ne posent pas eux-mêmes —
c'est `JunitVendorJars.ensurePresent()` qui l'écrit (une fois, jamais
réécrit s'il existe déjà) dès qu'il extrait au moins un jar, à la racine de
`EclipseProjectFiles.STAGING_DIR`. Effet de bord bienvenu : ce même
`.gitignore` couvre aussi `.clide.lock`/`.clide-daemon.log`/les
`.project`/`.classpath` mis de côté pendant le séjour — tout ce que
`EclipseProjectFiles.STAGING_DIR` héberge désormais, pas seulement
`jar-junit/`.

**Testé** : suite dédiée `JunitVendorJarsTest` (10 cas, `@TempDir` réel,
`resourceOpener` en `Map` mémoire — aucun vrai `clide.jar` nécessaire) —
extraction, chemin absolu rendu, atterrissage sous le bon dossier, ressource
manquante ignorée en silence, rien créé sur disque si rien n'est disponible,
un jar déjà présent jamais redemandé, deuxième appel idempotent, disponibilité
partielle sans se gêner, ordre du résultat déterministe (celui de
`VENDORED_JAR_NAMES`, pas celui du disque), `.gitignore` posé seulement si
quelque chose a été extrait, jamais réécrit s'il existe déjà. Rejoué de bout
en bout sur un clone PlantUML (`clide`) totalement neuf, `.clide/` sans aucun
JUnit : `.clide/tmp/jar-junit/` se peuple bien au premier démarrage du
daemon, `.classpath` liste les jars du projet avant ceux de `jar-junit/`,
`print_diagnostics errors` passe de 6058 à 6 (les 6 restantes n'ont rien à
voir avec JUnit — `RandomBeansExtension` introuvable, une dépendance de test
distincte, hors du périmètre de ce correctif), `run_test` sur
`JsonObjectTest`/`UrlBuilderTest`/`MathTest` rapporte 8/8, 20/20, 12/12 —
identique au comportement validé manuellement avant l'automatisation — et
`git status --porcelain` reste vide après `terminate`.

### Lancer les tests du projet ouvert (`run_test`, `run_tests`)

C'est la priorité n°2 du projet. Aucun build system n'est sollicité : jdtls
connaît déjà le classpath de test du projet (il vient de le compiler), donc
clide forke un JVM sur ce classpath **plus son propre jar** — qui embarque la
plateforme JUnit, les deux moteurs et le lanceur. Gradle ou Maven voudraient
dire un démon à réveiller et un build system à détecter ; ici c'est un
démarrage de processus.

C'est aussi la deuxième raison d'être du fat-jar : `clide.jar` contient
`junit-platform-console-standalone`, donc le moteur de test est déjà là, sans
rien demander au projet cible.

**Découpage.** `clide.test.TestRunnerMain` tourne dans le JVM forké et parle un
protocole de lignes volontairement bête (`SUMMARY`/`PASS`/`FAIL`/`SKIP`/
`NOCLASS`, champs séparés par tabulation et échappés) vers clide — c'est clide
qui a jdtls sous la main pour résoudre un chemin, pas lui.
`clide.test.ProjectTests` compose le classpath, forke, lit et met en forme.
`clide.test.TestSelector` traduit la notation `chemin:ligne:nom` en sélecteur
— logique pure, donc testable sans montage.

**Format de sortie**, le même `chemin:ligne:` que toutes les commandes
`find_*`, pour que chaque ligne se recopie dans `hover` ou `find_reference` :

```
run_test: 4 test(s), 2 passed, 2 failed in 570 ms
[failed] src/test/java/demo/CalcTest.java:22: deliberatelyFails
    expected: <99> but was: <5>
[passed] demo.CalcTest.addWorks
[failed] src/test/java/demo/CalcTest.java:27: deliberatelyBlowsUp
    java.lang.ArithmeticException: / by zero
    thrown at src/main/java/demo/Calc.java:9
```

Le `thrown at` vient de `java.project.resolveStackTraceLocation` : quand
l'exception ne vient pas de la ligne du test, les deux endroits sont nommés.

**Décisions prises, et pourquoi.**

- *Ordre du classpath* : le projet d'abord, `clide.jar` en dernier. Un projet
  qui embarque son propre JUnit garde le sien ; clide ne fournit que ce qui
  manque.
- *Pas de recompilation implicite.* `run_test` rapporte l'état du dernier
  build, pas celui des fichiers sur le disque. C'est un choix assumé (le
  rebuild coûte 9 à 12 s sur PlantUML), mais il rend une erreur probable :
  écrire un test puis le lancer avant `rebuild`. D'où un contrôle explicite —
  la classe est cherchée sur le classpath avant toute découverte, et son
  absence donne `demo.BrandNewTest is not in the compiled output - run rebuild
  first...` plutôt que le `TestEngine with ID 'junit-jupiter' failed to
  discover tests` de JUnit, qui envoie chercher au mauvais endroit.
- *Zéro test trouvé est une erreur*, pas un succès vide — un sélecteur mal
  orthographié serait sinon indiscernable d'une suite verte. Même raison que
  le `--fail-if-no-tests` de la cible `ant test`.
- *Timeouts* : 120 s pour `run_test`, 600 s pour `run_tests`, puis
  `destroyForcibly()`. Le message dit explicitement qu'il s'agit d'un timeout
  et non d'un échec de test.
- *Découverte limitée aux dossiers de sortie du projet*, pas au classpath
  entier : un scan complet parcourrait chaque jar, et pourrait rapporter les
  tests d'une dépendance comme étant ceux du projet.
- *Multi-modules refusé*, avec les modules listés. `java.project.getAll` les
  donne ; en choisir un au hasard lancerait les tests du mauvais module et
  rapporterait une suite propre.
- *JUnit 3 et 4 fonctionnent* (moteur Vintage embarqué), TestNG non.

**Conséquence à assumer** : `TestRunnerMain` pilote l'API Launcher, donc clide
compile désormais contre la **plateforme** JUnit (`junit-platform-launcher`,
`-engine`, `-commons`). Ce n'est plus de l'outillage de test mais une
dépendance de compilation de clide — présente dans le classpath principal côté
Ant, en `compileOnly` côté Gradle. Jupiter en est volontairement exclu, pour
qu'un `@Test` ne s'importe pas par accident dans du code de production. La
formule « clide, zéro dépendance » mérite désormais cette nuance.

**Un compteur ne doit jamais pouvoir invalider un fait.** Première version :
la JVM fille comptait les tests avec `countTestIdentifiers()` **au démarrage du
plan**, et clide se fiait à ce compteur et au code de sortie plutôt qu'aux
enregistrements reçus. Or un `@ParameterizedTest` est un *conteneur* dans le
plan : ses invocations sont enregistrées dynamiquement à l'exécution, donc
elles n'existent pas encore à cet instant. Mesuré sur PlantUML puis réduit en
cobayes :

| Classe | avant (`found/pass/fail/skip`, exit) | après |
|---|---|---|
| 5 cas paramétrés | `0 5 0 0` exit 2 | `5 5 0 0` exit 0 |
| 4 cas dont 1 rouge | `0 3 1 0` exit 2 | `4 3 1 0` exit 1 |
| 2 `@Test` + 3 param. + 4 répétés | `2 9 0 0` exit 0 | `9 9 0 0` exit 0 |
| classe `@Disabled` | `1 0 0 0` exit 0 | `1 0 0 1` exit 0 |
| paramétrés dans `@Nested` | `0 2 0 0` exit 2 | `2 2 0 0` exit 0 |

Une classe paramétrée **en échec** était donc rapportée « no test found », son
échec compris : une régression réelle cachée derrière un message qui ressemble
à un problème de configuration. Et la classe mixte annonçait « 2 test(s), 9
passed » — une ligne qui se contredit elle-même sans alerter personne.

Trois corrections, faites ensemble :

- `found = succeeded + failed + skipped`, compté **à mesure que ça arrive**.
  Immune par construction, pas par cas particulier : `@RepeatedTest` et
  `@TestFactory` sont réparés par la même ligne sans avoir été visés. Noter
  qu'appeler `countTestIdentifiers()` *à la fin* aurait aussi marché — le
  `TestPlan` s'enrichit des tests dynamiques — mais ferait dépendre le résultat
  d'un détail de mutation interne à JUnit.
- **Les totaux se déduisent des enregistrements, côté clide** (`ProjectTests.
  tally()`). `SUMMARY` ne sert plus qu'au chronomètre, et « zéro test » ne se
  déduit que de l'absence d'enregistrement, jamais d'un code de sortie. La même
  information voyageait deux fois sous deux formes ; c'est la mauvaise copie
  qui gagnait.
- Un **conteneur sauté** (classe `@Disabled`) fait compter ses descendants :
  JUnit ne signale que le conteneur, jamais les tests dessous.

Le correctif a fait apparaître un manque juste derrière : cinq cas paramétrés
donnaient cinq lignes rigoureusement identiques. Le `displayName` voyageait
déjà dans l'enregistrement sans être affiché. Il l'est maintenant quand il dit
autre chose que le nom de méthode — `[failed] ...:13: everyValueIsPositive
[3] -3`. Sur une classe à vingt cas, c'est la différence entre exploitable et
inutile.

**Le test qui manquait.** Rien dans la suite ne pouvait voir ce bug :
`TestRunnerMainTest` et `TestSelectorTest` ne couvrent que de la logique pure
(échappement, découpage, sélecteurs), aucun ne lançait JUnit.
`TestRunnerMainExecutionTest` le fait désormais — il **forke un JVM**, comme
clide, sur des classes cobayes couvrant chaque forme (paramétrée seule,
paramétrée en échec, mixte, désactivée, classe absente), et vérifie les lignes
*et* le code de sortie. Le fork est délibéré : `main()` finit par un
`System.exit()`, et le code de sortie fait partie du contrat au même titre que
la sortie. Coût, environ une demi-seconde par cas.

Les cobayes vivent dans le package **`fixture`**, hors de `clide`, pour une
raison précise : `ant test` sélectionne `--select-package clide`, qui ne
descend que dans `clide` et ses sous-packages. Les mettre sous `clide.` ferait
ramasser `ParameterizedFailing` par la suite de clide, et le build échouerait
sur un test dont l'échec est justement le comportement attendu. Vérifié :
0 cobaye dans le rapport de `ant test`.

Repassé en mutation, le filet attrape bien ce qu'il doit : le comptage figé au
démarrage du plan (3 tests rouges), un `ProjectTests` qui refait confiance au
`SUMMARY` (1), la disparition du `displayName` (1).

**Testé de bout en bout** sur deux projets cibles jetables (le premier : deux
classes, quatre tests dont deux en échec volontaire ; le second : cinq classes
couvrant paramétré, répété, mixte, désactivé, imbriqué — 21 tests) : `run_test` sur la
classe, sur une méthode qui passe, sur une méthode qui échoue ; `run_tests all`
et `run_tests failures` ; un test tout neuf lancé sans `rebuild` (message
explicite), puis après `rebuild` (vert) ; une méthode qui n'est pas un test
(« no test found »). **Pas encore vérifié sur PlantUML** — il reste à
confirmer que l'ordre du classpath tient face aux jars JUnit du projet, et que
la suite complète reste sous les 600 s — c'est en la lançant que le bug de
comptage ci-dessus est apparu, sur `UrlBuilderTest` et ses 20 cas.

### jdtls (Eclipse JDT Language Server)

`jdtls` n'est pas une dépendance Gradle : c'est un serveur autonome distribué
en `.tar.gz` depuis `download.eclipse.org` (domaine non accessible depuis la
sandbox Claude, testé : 403). Récupération via
`scripts/download_and_zip_jdtls.py`, qui télécharge le dernier build et le
reconditionne en `jdt-language-server-latest.zip` à la racine du repo
(recompression interne des jars avec zopfli — voir le docstring du script ;
nécessite `pip install zopflipy`).

**Choix délibéré : l'archive `.zip` est commitée dans git, `jdtls/` (son
contenu extrait) est ignoré.** Raison : `download.eclipse.org` n'est pas
accessible depuis la sandbox Claude, mais `github.com` l'est. En committant
l'archive et en la poussant sur GitHub, Claude peut cloner le repo dans sa
sandbox et récupérer l'archive avec, sans jamais contacter Eclipse — puis
l'extraire localement (le module `zipfile` de Python, ou `unzip`, suffit). Le
dossier extrait, lui, est dérivable de l'archive et n'a pas besoin d'être
versionné.

`JdtlsLauncher` (`src/main/java/clide/JdtlsLauncher.java`) reproduit la
logique du script `bin/jdtls.py` livré avec jdtls : trouve
`org.eclipse.equinox.launcher_*.jar` dans `jdtls/plugins`, choisit le dossier
de config partagé selon l'OS (`config_win`/`config_mac`/`config_linux`), et
lance `java -jar ... -data <dossier temporaire>`.

`clide` parle maintenant le protocole LSP réellement, via trois classes :

- **`Json.java`** — mini parseur/écrivain JSON maison (pas de dépendance
  externe, cohérent avec le principe zéro-dépendance de clide).
- **`LspClient.java`** — framing JSON-RPC (`Content-Length`), corrélation
  requête/réponse, thread de lecture séparé, file des notifications
  (`textDocument/publishDiagnostics` en particulier).
- **`JdtlsSession.java`** — orchestre tout : handshake `initialize` (avec
  `initializationOptions.settings.java.import.{gradle,maven}.enabled = false`
  pour éviter l'échec silencieux de l'import Gradle sans réseau — voir
  `JDTLS.md`), `initialized`, puis `java/buildWorkspace` (build complet du
  projet — voir plus bas pourquoi, plutôt que d'ouvrir chaque fichier),
  collecte des diagnostics publiés, et affichage d'un résumé. Le `stop()`
  tente un `shutdown`/`exit` LSP propre avant d'arrêter le processus. Chaque
  projet a son propre process daemon (un `JdtlsSession` chacun, son propre
  `.clide.lock` à sa racine) : plusieurs projets peuvent tourner en parallèle
  (ex. clide et PlantUML en même temps) sans se gêner, chacun dans son
  daemon.

**Découverte de passage à l'échelle** : ouvrir chaque fichier individuellement
(`textDocument/didOpen`) fonctionne bien sur un petit projet comme clide,
mais ne passe pas du tout à l'échelle sur PlantUML (3600 fichiers `.java`) —
jdtls les « reconcilie » un par un à environ 140 ms/fichier, soit plusieurs
minutes. `java/buildWorkspace` seul, sans aucun `didOpen`, obtient les mêmes
diagnostics pour tout le projet en moins d'une seconde (testé sur clide :
0,7 s). C'est cette approche qui est retenue.

Testé de bout en bout dans la sandbox Claude : compilation propre (0
diagnostic), erreur volontaire détectée à la bonne ligne puis disparaissant
une fois corrigée, réutilisation de la session entre deux lancements
successifs de `clide <chemin>` sur le même projet (le daemon reste up, pas
de nouveau handshake), et arrêt propre du sous-processus sur `exit`.

**Test sur PlantUML (clone frais, `git clone --depth 1`, sandbox Claude)** :
plus rien à préparer côté PlantUML. `.classpath`/`.project` n'ont besoin ni
d'être commités ni d'être générés via `./gradlew eclipse` (comme l'affirmait
une version antérieure de ce document) : jdtls les génère lui-même au
premier build — voir « État actuel » — en récupérant automatiquement les
jars commités dans `.clide/` (stubs ant/openpdf/teavm, JUnit et co).
Résultat mesuré : build complet des 3633 fichiers `.java` au démarrage du
daemon, **0 erreur**, 1300 warnings dans 584 fichiers ; `find_symbol`/
`find_declaration`/`find_implementation`/`find_reference`/`hover`/
`list_members`/`search_regex`/`print_diagnostics` répondent tous
correctement, ~0,25 s par session client une fois le daemon up.

### Capacités de jdtls — pistes trouvées en marge des commandes actuelles

Recherche faite en réponse à deux questions posées à l'usage (rien
d'appliqué au code ci-dessous, juste consigné pour ne pas le re-chercher
plus tard) :

**`find_symbol` ne remonte que des types par défaut (classes/interfaces/
enums/records/annotations), jamais des méthodes ou des champs** — confirmé
par un test dédié sur un mini-projet Java (`Foo`/`FooImpl`/`FooOtherImpl`/
`Caller`) : `find_symbol` sur un nom de classe fonctionne, sur un nom de
méthode renvoie "no symbol found". Ce n'est pas une limite du protocole LSP
en soi : jdtls a un paramètre d'initialisation,
`java.symbols.includeSourceMethodDeclarations` (défaut `false`), qui
contrôle précisément ça.

**Vérifié empiriquement (sandbox, `initializationOptions.settings.java.
symbols.includeSourceMethodDeclarations = true` ajouté temporairement dans
`JdtlsSession.initializeParams()`)** : avec le paramètre à `true`,
`find_symbol` sur un nom de méthode (`barMethod`, présent à la fois comme
déclaration abstraite dans l'interface `Foo` et comme override dans
`FooImpl`) renvoie bien les deux `[method]` — un par déclaration source,
pas juste un site "canonique". Confirmé aussi dans le bytecode de
`org.eclipse.jdt.ls.core` (`WorkspaceSymbolHandler`) : le flag ajoute un
second appel, `SearchEngine.searchAllMethodNames`, en plus du
`searchAllTypeNames` déjà fait pour les types.

**Côté champs en revanche, confirmé négatif** : avec le même flag à `true`,
`find_symbol` sur un nom de champ (`bazField`, champ public de `FooImpl`)
reste "no symbol found" — sans changement par rapport à `false`. Pas
seulement "pas testé" comme noté précédemment : le bytecode de
`WorkspaceSymbolHandler` ne contient absolument aucune recherche de champ
(zéro occurrence du mot "field", aucun appel style `searchAllFieldNames`) —
`workspace/symbol` chez jdtls ne sait tout simplement pas chercher un champ
par nom, avec ou sans ce paramètre. Aucune piste connue pour lever cette
limite-là côté jdtls actuellement.

**Ajouté pour de vrai depuis** dans `JdtlsSession.initializeParams()` —
`java.symbols.includeSourceMethodDeclarations = true` est maintenant envoyé
systématiquement à l'initialisation, plus seulement testé en sandbox :
`find_symbol` remonte donc aussi les méthodes en usage normal, pas
uniquement les types — re-vérifié sur PlantUML (`find_symbol
getStringBounder` → 15 déclarations `[method]` à travers tout le projet).
Toujours aucune piste côté champs (voir ci-dessus) — `find_symbol` ne les
trouvera jamais, avec ou sans ce paramètre.

**`find_reference` sur une méthode d'interface fonctionne correctement à
travers le polymorphisme** — vérifié sur le même mini-projet (test fait à
l'époque sous l'ancien nom `goto_references`, comportement inchangé depuis
le renommage) : pointé sur la déclaration abstraite `Foo.bar()`,
`find_reference` renvoie bien les 3 appels du projet, qu'ils soient faits
via une variable typée `Foo` (l'interface) ou typée `FooImpl`/
`FooOtherImpl` (les implémentations concrètes) ; pointé sur l'override
concret `FooImpl.bar()`, il ne renvoie que les 2 appels pertinents pour
cette méthode précise (pas celui qui appelle `FooOtherImpl.bar()`). Donc
pas de limite de ce côté, contrairement à une hypothèse envisagée un
temps.

**jdtls supporte aussi le Call Hierarchy standard LSP**
(`textDocument/prepareCallHierarchy` +
`callHierarchy/incomingCalls`/`callHierarchy/outgoingCalls`, visible dans
les imports de `JDTLanguageServer.java` côté jdtls) — une relation
différente de `references` : pas une liste plate d'usages, mais un arbre
navigable « qui appelle ceci » / « qu'est-ce que ceci appelle ». Aucune
commande clide ne l'utilise encore ; piste pour une future commande,
distincte de `find_reference`.

**jdtls expose des commandes hors LSP, via `workspace/executeCommand`** —
enregistrées par `JDTDelegateCommandHandler` dans `org.eclipse.jdt.ls.core`.
Trois sont utilisées par `run_test`/`run_tests` :

| Commande | Signature | Ce qu'elle rend |
|---|---|---|
| `java.project.getClasspaths` | `(uri, {scope:"test"})` | `{projectRoot, classpaths[], modulepaths[]}` |
| `java.project.isTestFile` | `(uri)` | `boolean` |
| `java.project.resolveStackTraceLocation` | `(frame, projectNames)` | l'URI du fichier source |

La liste complète, relevée dans le bytecode, comprend aussi
`java.project.getAll`, `listSourcePaths`, `updateClassPaths`, `java.decompile`,
`java.getFullyQualifiedName`, `java.edit.organizeImports`,
`java.navigate.openTypeHierarchy`, `java.vm.getAllInstalls` — piste pour de
futures commandes.

**Piège qui vaut une heure à qui ne l'a pas lu** : un argument qui est un objet
JSON doit être envoyé comme une *chaîne* JSON.

```
✗ arguments: [uri, {"scope":"test"}]     → -32001, Cannot read field "scope" because "options" is null
✓ arguments: [uri, "{\"scope\":\"test\"}"]
```

jdtls passe chaque argument à `JSONUtility.toModel()`, qui comprend un
`JsonElement`, une instance de la classe cible, ou une `String` qu'il parse
comme du JSON — et rend `null` pour tout le reste. Or lsp4j a déjà transformé
l'objet JSON en `Map` nue à ce moment-là. D'où le `null`, d'où le
`NullPointerException` emballé en erreur `-32001`.

**jdtls compile bien les sources de test** — vérifié : après `build()`,
`bin/test/demo/CalcTest.class` est présent. Et la boucle d'édition est propre :
créer un fichier de test puis `refreshChangedFiles()` + `build()` fait
apparaître son `.class`, le supprimer le fait disparaître. Pas de `.class`
fantôme, donc pas de test effacé qui continue de tourner.

Pour mémoire, l'équivalent côté IHM Eclipse de `find_reference`/
`find_declaration`/`find_implementation` est « Find References » / « Open
Declaration », qui opèrent toujours à partir d'une occurrence sélectionnée
dans un fichier ouvert — jamais d'un nom tapé à la main, exactement comme
les commandes `find_declaration`/`find_reference`/`find_implementation`/
`hover`/`list_members` de clide aujourd'hui. La recherche par nom seul, plus
générale (type OU méthode OU champ, avec un scope
déclarations/références/implémenteurs), correspond au dialogue séparé
« Search > Java... » d'Eclipse — c'est ce que `find_symbol` cherche à
approcher, avec la limite ci-dessus.

(Sans rapport direct avec clide, noté en passant : il existe un plugin
Claude Code officiel packageant jdtls, avec type search/call
hierarchy/refactoring/etc. clide reste volontairement indépendant, zéro
dépendance, avec son propre protocole texte.)

## Contrainte réseau importante

Le wrapper Gradle télécharge sa distribution depuis `services.gradle.org`, qui
n'est **pas** dans la liste des domaines autorisés pour Claude (vérifié : 403
depuis la sandbox).

**`build.xml` (Ant) est le fallback pour la sandbox Claude.** Testé de bout en
bout : `ant` (compile + jar `clide.jar`), `ant run` (stdin bien relié grâce à
`fork="true"`), `ant clean`. Nécessite un JDK 21 complet (pas juste un JRE) et
Ant — tous deux installables via apt depuis `archive.ubuntu.com`/
`security.ubuntu.com`, whitelistés. Point d'attention : la sandbox Claude ne
contient de base qu'un JRE 21 sans `javac` ; il faut `apt-get update &&
apt-get install -y openjdk-21-jdk-headless ant` avant de builder — pas encore
persistant d'une session à l'autre.

**Maven Central (`repo1.maven.org`) n'est pas accessible non plus** depuis la
sandbox Claude — ni directement, ni via les releases GitHub du projet JUnit.
C'est la raison pour laquelle les jars JUnit sont commités dans `lib/` plutôt
que résolus au build : même raisonnement que pour l'archive jdtls. Côté Gradle,
`mavenCentral()` fonctionne normalement sur une machine de développement.

## Prochaines étapes envisagées (non implémentées)

- Commande de compilation d'un projet cible (ex. via Ant pour PlantUML, qui ne
  nécessite pas de téléchargement de dépendances).
- ~~Commande de lancement d'un test unique.~~ Faite : `run_test`/`run_tests`,
  voir plus haut. Reste à valider sur PlantUML, et à décider si un paramètre de
  module doit être ajouté pour les dépôts multi-modules.
- ~~Requêtes sémantiques supplémentaires via `JdtlsSession`/`LspClient` :
  `callHierarchy` (...), `typeHierarchy`, etc.~~ Faites :
  `find_callers`/`find_callees` (`textDocument/prepareCallHierarchy` +
  `callHierarchy/incomingCalls`/`outgoingCalls`) et
  `find_supertypes`/`find_subtypes` (`textDocument/prepareTypeHierarchy` +
  `typeHierarchy/supertypes`/`subtypes`), un cran à chaque fois plutôt que
  toute la hiérarchie d'un coup, position chaînable dans un nouvel appel
  pour aller plus loin (voir CLAUDE.md, la boucle `--lua` existe pour ça).
  Piège découvert en testant clide sur lui-même : contrairement à
  `TypeHierarchyItem`, le `selectionRange` d'un `CallHierarchyItem` que
  jdtls renvoie pour `incomingCalls`/`outgoingCalls` n'est PAS le nom de la
  méthode appelante/appelée — c'est le site d'appel lui-même (même span que
  `fromRanges`/`toRanges`), verifié empiriquement le 2026-08-23. Corrigé en
  retrouvant la vraie déclaration via l'arbre `textDocument/documentSymbol`
  du fichier, apparié par `(uri, range.start.line)` — pas besoin de
  comparer les noms, cette ligne suffit à désambiguïser. Autre surprise :
  `textDocument/prepareCallHierarchy` est bien plus permissif que ne le
  laisse penser la spec LSP — pointé sur un champ, il répond avec les
  méthodes qui le lisent/l'écrivent (comme la vue Call Hierarchy d'Eclipse
  le fait aussi pour les champs) ; pointé sur un type, avec les
  constructeurs qui l'appellent implicitement ou non. `NOT_A_METHOD` ne
  déclenche donc que quand jdtls ne résout vraiment rien du tout (rare).
  `prepareTypeHierarchy`, lui, se comporte comme attendu (`NOT_A_TYPE`
  propre sur un champ/une méthode).
  (`definition`/`typeDefinition`/`implementation`/`references` faits — voir
  `find_declaration`/`find_reference`/`find_implementation` ci-dessus ; voir
  `JDTLS.md`, section 2).
- ~~Les niveaux 1-3 de SYMBOLS.md (`Classe::membre`, `Classe`/`Outer.Inner`
  seule, `NomFichier.java` seul), spécifiés mais non implémentés.~~ Faits,
  2026-08-23 : `PositionParser.parse()` lui-même étendu avec un accès
  `JdtlsSession` plutôt qu'un résolveur séparé en amont (choix délibéré,
  malgré la dépendance circulaire `clide.core`↔`clide.jdtls` que ça crée —
  sans conséquence en pratique, le projet n'utilise pas de `module-info.java`)
  — dispatch sur la forme du jeton : `::` présent → `Classe::membre` ;
  suffixe `.java` sans séparateur → raccourci par nom de fichier (entièrement
  hors ligne, une recherche `FilesRepository` par nom) ; identifiant nu
  (éventuellement à points) → `Classe`/`Outer.Inner` seule ; sinon
  `MALFORMED_POSITION` inchangé. Deux codes d'erreur ajoutés,
  `SYMBOL_NOT_FOUND`/`AMBIGUOUS_SYMBOL`, un par issue (zéro/plusieurs
  candidats) plutôt qu'un par grammaire.

  Deux découvertes empiriques ont façonné l'implémentation, faites en testant
  clide sur lui-même : le `containerName` qu'un hit `workspace/symbol` porte
  pour une classe est le nom qualifié de la portée englobante (le paquet pour
  une classe de premier niveau, `paquet.Externe` pour une classe imbriquée
  `Externe.Interne`) — exactement ce qu'il fallait pour désambiguïser
  `Outer.Inner` d'une classe `Inner` homonyme ailleurs dans le projet. Et le
  champ `name` d'un noeud `textDocument/documentSymbol` pour une
  méthode/constructeur porte la liste de types des paramètres telle quelle
  (`"goToPosition(String, Position)"`), jamais juste le nom nu — ce qui donne
  l'arité gratuitement, sans reparser le texte source de la ligne (fragile
  avec des génériques imbriqués) : `Classe::methode(N)` compte les virgules
  de ce texte à profondeur d'imbrication `<...>` nulle.

  Résolution de chaque niveau indépendante, sans repli automatique d'un
  niveau vers le suivant — voir SYMBOLS.md, "Principe cardinal" : un tel
  repli serait exactement la résolution silencieuse que ce principe interdit.
  `CommandDispatcher.validate()` (la passe de surface avant même qu'une
  commande ne s'exécute) a dû se scinder en deux : les niveaux hors ligne
  (canonique, nom de fichier seul) y sont résolus en entier, comme avant ;
  `Classe::membre`/`Classe seule` n'y sont vérifiés que grammaticalement
  (`PositionParser.preValidate()`), leur résolution réelle restant réservée à
  l'exécution de la commande, seul moment où jdtls est garanti démarré et à
  jour (`CommandDispatcher.dispatch()` ne relance la session/resynchronise
  qu'après `validateParams()`) — sans quoi une position pourtant valide
  aurait pu heurter une session pas encore prête.
- Attendre réellement la fin d'indexation (`language/status` →
  `Started`/`ServiceReady`) plutôt que le délai fixe actuel dans
  `JdtlsSession.waitForServiceReady` — le délai fixe s'est avéré suffisant
  aussi sur PlantUML (3600 fichiers, build complet au démarrage du daemon),
  mais l'attente réelle resterait plus propre.
- ~~Une commande pour supprimer les imports non utilisés dans un ou plusieurs
  fichiers Java.~~ Faite, 2026-08-24 : `remove_unused_imports <path regex>`,
  le même genre de `<path regex>` que `search_regex` (matché sur le chemin
  relatif au projet, forward slashes) plutôt qu'un chemin unique — la demande
  explicite. Portée volontairement limitée aux imports *non utilisés* :
  aucun réordonnancement, regroupement ni collapse de wildcard — c'est le
  travail du `source.organizeImports` de jdtls, délibérément pas appelé ici.

  Détection sans parser aucun Java : filtrage des diagnostics du dernier
  build (les mêmes que `print_diagnostics`) sur l'id de problème propre à
  Eclipse pour "The import ... is never used" — `268435844`, trouvé de façon
  empirique (aucune doc stable ne le nomme) en imprimant un diagnostic brut
  sur un import volontairement inutilisé et en lisant son champ `code` :
  `{"range":{...},"severity":2,"code":"268435844","source":"Java","message":
  "The import java.util.List is never used"}`. Comparaison sur ce code plutôt
  que sur le texte du message, qui n'est pas un contrat. Nouvelle méthode
  `JdtlsSession.unusedImportLines()`, dérivée de la même `diagnosticsByUri`
  que `diagnosticsReport()` — rien de neuf n'est demandé à jdtls.

  Écriture directe (`Files.write()`), sans passer par un `WorkspaceEdit` de
  jdtls ni par un appel à `backupBeforeModification()` : l'architecture de
  `Transaction` s'est avérée ne pas en avoir besoin — le `Snapshot` pris à
  l'ouverture d'une transaction couvre déjà tout fichier `.java` source, donc
  `rollback_transaction`/`diff_transaction` fonctionnent sur ce que cette
  commande écrit sans rien de plus. Chaque ligne candidate est revérifiée
  contre le fichier tel qu'il est là, maintenant (elle doit encore ressembler
  à `import ...;`) avant suppression — défensif, au cas où le fichier aurait
  changé depuis le diagnostic ; suppression du bas vers le haut pour ne
  jamais décaler un numéro de ligne encore en attente. `<path regex>` qui ne
  matche aucun fichier du projet est `NO_FILES_FOUND` ; matcher des fichiers
  réels mais déjà propres ne l'est pas — l'ambiguïté que la conception a
  tranchée avant l'implémentation (voir la discussion avec l'utilisateur).

  Vérifié en conditions réelles sur clide lui-même (fichier de test avec un
  `import java.util.Map;` volontairement inutilisé) : suppression correcte
  d'un seul fichier et de plusieurs à la fois (regex groupée), `0 error(s)`
  après resynchronisation, `diff_transaction` montrant exactement la ligne
  supprimée, `rollback_transaction` restaurant le fichier à l'identique, et
  `commit_transaction` rendant la suppression définitive.
