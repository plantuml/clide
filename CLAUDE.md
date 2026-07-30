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
    connaître à l'avance le fichier/la ligne — ce qui manquait en amont de
    `goto_*` (voir `TODO.md`, retiré une fois cette commande faite) : il
    fallait grepper soi-même pour trouver la ligne avant de pouvoir appeler
    `goto_definition`. Bâtie sur `workspace/symbol` (`JdtlsSession.findSymbol`) ;
    **le matching est entièrement délégué à jdtls** (typiquement flou/camelCase
    en pratique, pas une égalité stricte) — clide ne filtre rien lui-même en
    plus, par choix : `find_symbol UGraphic` peut très bien remonter aussi
    `UGraphicSvg`, `UGraphicNull`, etc. Chaque résultat est préfixé par la
    nature du symbole entre crochets (`[class]`, `[interface]`, `[method]`,
    `[field]`, `[constructor]`, `[enum]`, `[constant]`, `[package]`,
    `[variable]`, `[property]`, `[function]`, `[enum member]`, `[struct]`, ou
    `[symbol]` si le code `SymbolKind` LSP n'est pas reconnu), suivi du même
    format `chemin/relatif.java:ligne: contenu de la ligne` que `goto_*` —
    volontairement, pour pouvoir recopier tel quel le fichier/la ligne d'un
    résultat dans un `goto_definition`/`goto_implementation` juste après.

    **Testé de bout en bout** (clone GitHub frais de `plantuml/clide`, jdtls
    extrait, self-test — `clide` sur lui-même) : `find_symbol JdtlsSession`
    renvoie bien `[class] src/main/java/clide/jdtls/JdtlsSession.java:34: public
    class JdtlsSession {`.
  - `goto_definition <symbole>` (notation `<chemin fichier>:<ligne>:<nom>`,
    voir la section dédiée plus bas) → où est réellement
    définie la déclaration du symbole (pas juste un usage). `goto_type_definition`
    même paramètre unique `<symbole>` → où est définie la classe/interface du type déclaré du
    symbole (pas la déclaration du symbole lui-même, et pas son type
    d'exécution : le LSP ne connaît que le type statique déclaré). La ligne est
    1-based (comme affichée en lisant le fichier) ; le symbole est cherché comme
    mot entier sur cette ligne (`\bsymbole\b`), clide en déduit la colonne — pas
    de comptage de caractères à faire. Les deux commandes affichent toutes les
    locations renvoyées (`chemin/relatif.java:ligne: contenu de la ligne`), ou
    `"<no definition found>"` si vide. Logique partagée dans
    `JdtlsSession.goToPosition` ; `GotoDefinitionCommand`/`GotoTypeDefinitionCommand`
    ne diffèrent que par la méthode LSP appelée (`textDocument/definition` vs
    `textDocument/typeDefinition`), via la classe intermédiaire
    `GotoPositionCommand`. Pas de `textDocument/didOpen` envoyé avant la requête
    (repose sur le modèle déjà construit par le dernier `build()`, fait
    automatiquement au démarrage du daemon).

    **Testé de bout en bout, clide sur lui-même** (clone GitHub frais de
    `plantuml/clide`, jdtls extrait, `ant run`) : `goto_definition` sur une
    variable renvoie sa déclaration locale (ex. `command` dans `Main.java` →
    ligne de `final Command command = registry.find(keyword);`) ;
    `goto_type_definition` sur ce même symbole renvoie directement la classe de
    son type déclaré (`public abstract class Command {`), sans repasser par la
    déclaration locale. Confirmé aussi sur `context`/`ClideContext`. **Confirmé
    au passage : aucun `textDocument/didOpen` préalable n'est nécessaire**, la
    requête aboutit directement sur le modèle du dernier `java/buildWorkspace` —
    l'incertitude notée plus haut est levée (au moins sur un petit projet comme
    clide ; à revalider sur un projet de la taille de PlantUML). Cas d'erreur
    (symbole absent de la ligne donnée) : message clair,
    `Symbol 'foobar' not found on line 55 of ...`.
  - `goto_implementation <symbole>` → mêmes
    paramètre et même comportement que `goto_definition`/`goto_type_definition`
    (troisième sous-classe de `GotoPositionCommand`, aucune logique
    supplémentaire) mais interroge `textDocument/implementation` : quelles
    classes/méthodes implémentent réellement le symbole visé — typiquement une
    méthode abstraite ou d'interface. C'est la question de polymorphisme posée
    dès l'origine du projet.

    **Testé de bout en bout, clide sur lui-même** (re-testé après l'ajout de
    `hover`/`list_members`/`goto_references` — la liste évolue avec le nombre
    de commandes) : `goto_implementation` sur `executeCommand` (méthode
    abstraite de `Command.java`) renvoie exactement les 10 implémentations
    concrètes existantes (`DisconnectCommand` — partagée par `exit`/`quit` —,
    `FindSymbolCommand`, `GotoPositionCommand` — partagée par les quatre
    commandes `goto_*`, listée une seule fois, pas quatre —, `HelpAiCommand`,
    `HelpCommand`, `HoverCommand`, `ListMembersCommand`,
    `PrintDiagnosticsCommand`, `ResearchRegexCommand`, `TerminateCommand`),
    sans bruit (ni la déclaration abstraite, ni les sites d'appel
    `command.executeCommand(...)` qu'un grep aurait remontés).
  - `goto_references <symbole>` → même paramètre
    que les trois autres `goto_*` (quatrième sous-classe de
    `GotoPositionCommand`), mais interroge `textDocument/references` :
    partout où `symbole` est réellement utilisé dans le projet — l'inverse de
    `goto_implementation` (qui part d'une interface/méthode abstraite vers
    ses implémentations concrètes ; `goto_references` part de n'importe quel
    symbole vers tous ses usages réels). Envoie `includeDeclaration: false`
    dans le `context` de la requête LSP : la déclaration est déjà connue
    (c'est l'entrée de la commande), seuls les vrais usages comptent — avec
    `includeDeclaration: true`, une méthode jamais appelée remonterait quand
    même 1 résultat (sa propre déclaration), ce qui fausserait justement la
    réponse à « cette méthode est-elle vraiment appelée quelque part ? ».
    `JdtlsSession.goToPosition` a été élargi (nouvelle surcharge à 5
    paramètres) pour accepter ce `context` optionnel, plutôt que de dupliquer
    la logique dans une méthode séparée comme `hover`/`listMembers` — les
    trois autres `goto_*` continuent de passer par la surcharge à 4
    paramètres (`context` implicitement `null`).

    **Testé de bout en bout, clide sur lui-même** : `goto_references` sur
    `getCurrentSession` (déclarée ligne 40 de `ClideContext.java`) renvoie
    exactement ses 6 sites d'appel réels, sans la déclaration elle-même —
    confirme que `includeDeclaration: false` fonctionne comme prévu.
  - `hover <symbole>` → signature/Javadoc que jdtls
    connaît pour ce symbole précis, à cet endroit précis (pas un autre
    emplacement comme `goto_*` — `hover` explique le symbole où il se trouve).
    Même résolution de position que `goto_*` (mot entier sur la ligne, colonne
    déduite). Bâtie sur `textDocument/hover` (`JdtlsSession.hover`). Le texte
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
    Même résolution de position que `goto_*`/`hover`, mais ici pour désigner
    quel type inspecter plutôt qu'où sauter/quoi expliquer. Bâtie sur
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

`goto_definition`, `goto_type_definition`, `goto_implementation`,
`goto_references`, `hover` et `list_members` prenaient chacune trois
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
quelle — c'est le format dans lequel `find_symbol`/`goto_*`/`hover`/
`list_members` impriment déjà chacun de leurs résultats (voir
`JdtlsSession.formatLocation()`), donc un résultat recopié tel quel depuis
l'un de ces retours et renvoyé en paramètre d'un `hover`/`goto_*` suivant
fonctionne sans avoir à le retoucher à la main. `projectRoot.resolve()` seul
ne suffit pas pour une URI : sous Windows, `"file:///C:/..."` n'est pas un
chemin Windows valide (le `:` après la lettre de lecteur — ou après `file` —
fait échouer le parseur de chemin Windows, `InvalidPathException: Illegal
char <:> at index ...`), d'où le passage par `java.net.URI`/`Paths.get(URI)`
plutôt que par `Path.resolve()` dès qu'un `pathArgument` commence par `file:`
(insensible à la casse).

**Testé de bout en bout** (clone GitHub frais, jdtls extrait de l'archive
commitée, build Ant, `clide` sur lui-même) : `hover`, `goto_implementation` et
`list_members` avec la nouvelle notation à paramètre unique (ex.
`src/main/java/clide/core/Command.java:56:needsJdtlsSession`) renvoient
exactement les mêmes résultats qu'avant ce refactor (respectivement le
Javadoc de la méthode, ses 6 implémentations concrètes réelles, et les 10
membres de `Command` — `getParamTypes()` inclus, la nouvelle méthode) ;
`goto_definition` sur un chemin inexistant échoue en `?SYNTAX ERROR: Not a
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
  (`search_regex`), `GotoDefinitionCommand`, `GotoTypeDefinitionCommand`,
  `GotoImplementationCommand` (les trois via la classe intermédiaire
  `GotoPositionCommand`, seule exception au principe « une classe = une
  commande », justifiée par une logique de dispatch strictement identique
  entre les trois — voir plus bas).

Ajouter une commande = ajouter une classe `clide.core` qui étend `Command`
et l'enregistrer dans `Main.commands` ; aucune autre modification de `Main`
n'est nécessaire.

Build :
```
./gradlew run
```
puis taper `help` au prompt.

### jdtls (Eclipse JDT Language Server)

`jdtls` n'est pas une dépendance Gradle : c'est un serveur autonome distribué
en `.tar.gz` depuis `download.eclipse.org` (domaine non accessible depuis la
sandbox Claude, testé : 403). Installation via `scripts/install_jdtls.py`
(stdlib Python uniquement), qui télécharge le dernier build dans
`jdt-language-server-latest.tar.gz` à la racine du repo, puis l'extrait dans
`jdtls/`.

**Choix délibéré : l'archive `.tar.gz` est commitée dans git, `jdtls/` (son
contenu extrait) est ignoré.** Raison : `download.eclipse.org` n'est pas
accessible depuis la sandbox Claude, mais `github.com` l'est. En committant
l'archive et en la poussant sur GitHub, Claude peut cloner le repo dans sa
sandbox et récupérer l'archive avec, sans jamais contacter Eclipse — puis
l'extraire localement (le module `tarfile` de Python, ou `tar`, suffit). Le
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

**Test sur PlantUML (clone frais, `git clone --depth 1`)** : le daemon
démarre et build techniquement (rapide, pas de blocage), mais rapporte « projet non
reconnu » — PlantUML n'a pas encore de `.classpath`/`.project` commité
comme clide. Même traitement nécessaire là-bas (`./gradlew eclipse` +
commit) avant que `clide` y soit vraiment utile.

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

## Prochaines étapes envisagées (non implémentées)

- Commande de compilation d'un projet cible (ex. via Ant pour PlantUML, qui ne
  nécessite pas de téléchargement de dépendances).
- Commande de lancement d'un test unique.
- Requêtes sémantiques supplémentaires via `JdtlsSession`/`LspClient` :
  `references`, `callHierarchy`, `typeHierarchy`, etc. (`definition`/
  `typeDefinition`/`implementation` faits — voir `goto_definition`/
  `goto_type_definition`/`goto_implementation` ci-dessus ; voir `JDTLS.md`,
  section 2).
- Attendre réellement la fin d'indexation (`language/status` →
  `Started`/`ServiceReady`) plutôt que le délai fixe actuel dans
  `JdtlsSession.waitForServiceReady` — suffisant sur un petit projet comme
  clide, à revalider sur PlantUML.
