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
  - `open_project <chemin>` → ouvre un projet Java à ce chemin : démarre un
    jdtls dédié si besoin (une session par projet, plusieurs projets peuvent
    être ouverts en parallèle), fait le handshake LSP complet
    (`initialize`/`initialized`, import Gradle/Maven désactivé — voir
    `JDTLS.md`), déclenche un build complet (`java/buildWorkspace`) et
    affiche un résumé des diagnostics de compilation (erreurs/warnings, avec
    fichier + ligne + message). Rappeler `open_project` sur un chemin déjà
    ouvert réutilise la session (pas de nouveau handshake) et relance juste
    le build. Devient le projet « courant » pour `print_diagnostics`.
  - `print_diagnostics <all|errors>` → réaffiche les diagnostics du dernier
    build du projet courant (`all` : tout, `errors` : erreurs uniquement).
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
    Nécessite un `open_project` préalable, même message d'erreur que `goto_*`
    sinon.

    **Testé de bout en bout** (clone GitHub frais de `plantuml/clide`, jdtls
    extrait, self-test — `clide` sur lui-même) : `find_symbol JdtlsSession`
    renvoie bien `[class] src/main/java/clide/jdtls/JdtlsSession.java:34: public
    class JdtlsSession {`.
  - `goto_definition <chemin fichier> <ligne> <symbole>` → où est réellement
    définie la déclaration du symbole (pas juste un usage). `goto_type_definition`
    même signature → où est définie la classe/interface du type déclaré du
    symbole (pas la déclaration du symbole lui-même, et pas son type
    d'exécution : le LSP ne connaît que le type statique déclaré). La ligne est
    1-based (comme affichée en lisant le fichier) ; le symbole est cherché comme
    mot entier sur cette ligne (`\bsymbole\b`), clide en déduit la colonne — pas
    de comptage de caractères à faire. Les deux commandes affichent toutes les
    locations renvoyées (`chemin/relatif.java:ligne: contenu de la ligne`), ou
    `"<no definition found>"` si vide. Nécessitent un `open_project` préalable
    (utilisent le projet courant). Logique partagée dans
    `JdtlsSession.goToPosition` ; `GotoDefinitionCommand`/`GotoTypeDefinitionCommand`
    ne diffèrent que par la méthode LSP appelée (`textDocument/definition` vs
    `textDocument/typeDefinition`), via la classe intermédiaire
    `GotoPositionCommand`. Pas de `textDocument/didOpen` envoyé avant la requête
    (repose sur le modèle déjà construit par le dernier `open_project`/`build()`).

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
  - `goto_implementation <chemin fichier> <ligne> <symbole>` → mêmes
    paramètres et même comportement que `goto_definition`/`goto_type_definition`
    (troisième sous-classe de `GotoPositionCommand`, aucune logique
    supplémentaire) mais interroge `textDocument/implementation` : quelles
    classes/méthodes implémentent réellement le symbole visé — typiquement une
    méthode abstraite ou d'interface. C'est la question de polymorphisme posée
    dès l'origine du projet.

    **Testé de bout en bout, clide sur lui-même** : `goto_implementation` sur
    `executeCommand` (méthode abstraite de `Command.java`) renvoie exactement
    les 6 implémentations concrètes (`ExitCommand`, `GotoPositionCommand` —
    partagée par les trois commandes `goto_*`, listée une seule fois, pas
    trois —, `HelpCommand`, `OpenProjectCommand`, `PrintDiagnosticsCommand`,
    `ResearchRegexCommand`), sans bruit (ni la déclaration abstraite, ni les
    sites d'appel `command.executeCommand(...)` qu'un grep aurait remontés).
  - `hover <chemin fichier> <ligne> <symbole>` → signature/Javadoc que jdtls
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
  - `list_members <chemin fichier> <ligne> <symbole>` → liste les membres
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
open_project /path/to/project
search_regex /src \*.java foo\w+
print_diagnostics all
```

on écrit :
```
open_project
/path/to/project
search_regex
/src
\*.java
foo\w+
print_diagnostics
all
```

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
  tente un `shutdown`/`exit` LSP propre avant d'arrêter le processus.
  `Main.java` garde une session par projet ouvert (`Map<Path,
  JdtlsSession>`), pour pouvoir travailler sur plusieurs projets à la fois
  (ex. clide et PlantUML en parallèle).

**Découverte de passage à l'échelle** : ouvrir chaque fichier individuellement
(`textDocument/didOpen`) fonctionne bien sur un petit projet comme clide,
mais ne passe pas du tout à l'échelle sur PlantUML (3600 fichiers `.java`) —
jdtls les « reconcilie » un par un à environ 140 ms/fichier, soit plusieurs
minutes. `java/buildWorkspace` seul, sans aucun `didOpen`, obtient les mêmes
diagnostics pour tout le projet en moins d'une seconde (testé sur clide :
0,7 s). C'est cette approche qui est retenue.

Testé de bout en bout dans la sandbox Claude : compilation propre (0
diagnostic), erreur volontaire détectée à la bonne ligne puis disparaissant
une fois corrigée, réutilisation de la session sur un second appel à
`open_project` sur le même chemin, et arrêt propre du sous-processus sur
`exit`.

**Test sur PlantUML (clone frais, `git clone --depth 1`)** : `open_project`
fonctionne techniquement (rapide, pas de blocage), mais rapporte « projet non
reconnu » — PlantUML n'a pas encore de `.classpath`/`.project` commité
comme clide. Même traitement nécessaire là-bas (`./gradlew eclipse` +
commit) avant que `open_project` y soit vraiment utile.

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
