# Notation Symbol — clide

Spécification de la notation utilisée par clide pour désigner un symbole (fichier, classe, champ, méthode) dans le code Java. Objectif : rester la plus courte possible tout en restant non ambiguë, en s'appuyant sur des conventions déjà connues (Java, outils Unix) plutôt qu'en inventant de nouveaux sigles.

## Notation canonique PATH_LINE_COLUMN — la seule implémentée aujourd'hui

`chemin/complet.java:ligne:colonne:nom` — le plus précis.

On commence à compter à 1, pour la ligne comme pour la colonne.

Il y a un contrôle de cohérence : "nom" doit bien être présent dans la colonne spécifiée.

**État d'implémentation.** C'est aujourd'hui la seule notation acceptée par
clide, et la seule qu'il produit. Les niveaux 2 à 4 de l'échelle ci-dessous
restent une spécification, pas du code : les envoyer donne
`?ERROR MALFORMED_POSITION`. Restriction volontaire et temporaire — une seule
forme à écrire, une seule à parser, une seule à documenter, le temps que le
reste se stabilise.

Ce que la colonne obligatoire change, concrètement :

- **Plus aucune résolution implicite.** Sans colonne, clide prenait la première
  occurrence du nom sur la ligne et signalait le cas par un
  `!WARNING AMBIGUOUS_NAME_ON_LINE`. Ce warning n'existe plus : chaque
  occurrence a sa propre colonne, donc `a.calculer(b.calculer())` désigne l'une
  *ou* l'autre, jamais « la première ».
- **Le nom devient un contrôle, pas une décoration.** Un fichier édité entre le
  moment où une position a été imprimée et celui où elle est renvoyée a vu ses
  colonnes bouger. Le jeton périmé est alors refusé (`NAME_NOT_AT_COLUMN`, dont
  le hint donne les colonnes réelles) au lieu de répondre sur ce qui se trouve
  désormais à cet endroit. `NAME_NOT_ON_LINE` reste distinct : le nom n'est nulle
  part sur la ligne, corriger la colonne n'y changerait rien.
- **Symétrie entrée/sortie.** Ce que clide imprime est *exactement* la notation
  qu'il accepte : `chemin:ligne:colonne:nom` suivi d'une espace puis du texte de
  la ligne. Un résultat se recopie dans la commande suivante sans rien ajouter
  ni recompter. Le `nom` imprimé est relu depuis la ligne source à cette
  colonne — donc `Box` et non `Box<T extends Comparable<T>>` comme le nomme
  jdtls : ce qui sort est acceptable en entrée par construction.

Détails de mise en œuvre :

- Numérotation **1-based** pour ligne et colonne côté protocole, même si jdtls/LSP travaille en 0-based en interne. Conversion centralisée en un seul point à la frontière avec jdtls (`JdtlsResponses.oneBased()` / `positionParams()`). Choix motivé par la cohérence avec les autres outils manipulés dans une même session (lecture de fichier, grep, javac, stack traces), tous en 1-based.
- Le contrôle de cohérence se fait sur le **mot entier** (`\bnom\b`) — jamais par recherche de sous-chaîne naïve, qui matcherait à tort `foo` dans `foobar`.
- Parsing sans ambiguïté malgré les `:` déjà présents dans les chemins Windows (`C:\...`) : les trois derniers segments sont toujours ligne, colonne et nom ; tout ce qui précède est le chemin, quels que soient les `:` qu'il contient.

## Principe cardinal

**Toute ambiguïté doit produire une erreur explicite**, listant les candidats trouvés — jamais de résolution silencieuse vers le premier match trouvé. Ce principe s'applique à chaque niveau ci-dessous, sans exception.

## Échelle de résolution (niveaux 2 à 4 : spécifiés, non implémentés)

Du plus court/robuste au plus précis, à utiliser en cascade — retomber au niveau suivant dès qu'un niveau échoue par ambiguïté :

1. `Classe::membre` ou `Outer.Inner::membre` — le plus court, insensible aux déplacements de ligne (édition, refactor)
2. `Classe` ou `Outer.Inner` seule — référence la classe elle-même
3. `NomFichier.java:ligne:colonne:nom` — raccourci par nom de fichier, si celui-ci est unique dans le projet
4. `chemin/complet.java:ligne:colonne:nom` — le plus précis, toujours valide, sert de filet de sécurité (**le seul actif aujourd'hui**)

## Détail par niveau

### 2. Nom de fichier seul

Un jeton sans séparateur de chemin (`/` ou `\`), terminé par `.java`, déclenche une recherche par nom de fichier dans tout le projet. Pas de sigil dédié : l'absence de séparateur suffit à distinguer ce cas d'un chemin complet.

- Valide uniquement si un seul fichier de ce nom existe dans le projet ; sinon, erreur explicite listant les fichiers trouvés.

### 3. Classe::membre

`::` — repris tel quel de la syntaxe native Java pour les références de méthode.

- `Classe::champ` — nom nu, pour un champ
- `Classe::methode()` — parenthèses **obligatoires**, même sans paramètre ; c'est ce qui distingue sans ambiguïté un champ d'une méthode homonyme (Java autorise les deux dans la même classe)
- `Classe::methode(1)` — arité optionnelle entre parenthèses, pour lever l'ambiguïté en cas de surcharges
  - Limite connue : deux surcharges de même arité mais de types différents (`methode(String)` / `methode(FontConfiguration)`) restent ambiguës malgré l'arité — retombe alors sur le chemin complet

Valide uniquement si la classe et le membre sont chacun uniques dans le projet ; sinon, erreur explicite.

### 4. Classe seule et classes internes

Un identifiant nu, sans `::` ni suite, référence la classe elle-même. Pas de sigil nécessaire : la grammaire des autres niveaux (présence de `.java`, de `/` ou `\`, ou de `::`) suffit à ne jamais confondre ce cas avec les précédents.

- Classes internes : notation par point, `Outer.Inner` — reprend la qualification Java native, généralise sans effort à l'imbrication arbitraire (`Outer.Middle.Inner`)
- Combinable avec `::` pour cibler un membre : `Outer.Inner::methode()`
- Limite : les classes anonymes n'ont pas de nom simple et ne peuvent être ciblées par aucune de ces notations — seul le chemin complet (éventuellement sans nom de symbole) peut les atteindre

## Contraintes de build associées

clide doit se builder avec **Ant**, sans aucune dépendance réseau, à partir d'un simple checkout GitHub — même contrainte déjà appliquée à jdtls (zip vendoré dans le repo, ~49 Mo). Toute dépendance future (luajava : ~1,5 Mo en tout pour l'API + les natifs desktop Linux/Windows/macOS) suit le même principe de vendoring dans `lib/`.

## Scripting Lua (piste en cours de réflexion)

- Garder les deux modes d'interaction : protocole texte commande par commande, et scripts Lua pour les séquences connues à l'avance
- Bibliothèque envisagée : `gudzpoz/luajava` (JNI, activement maintenu, Lua 5.1–5.5 et LuaJIT)
- Chaque commande `@Keyword` exposée comme fonction Lua globale, via réflexion sur le `CommandRegistry` existant
- Résultats structurés via des `record` Java convertis génériquement en tables Lua (un seul convertisseur générique plutôt qu'un binding par commande)
- Un script `lua_exec` s'exécute dans une transaction englobante automatique (commit/rollback implicite)
