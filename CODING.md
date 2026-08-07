# CODING.md — Conventions de code pour le source de clide

Règles de style pour qui écrit ou modifie le code Java de clide lui-même
(humain ou IA) — sans rapport avec l'utilisation de clide comme outil, voir
`CLAUDE.md` pour ça.

## Construire et vérifier : `ant`, et rien d'autre

**Toute modification se compile, se teste et s'exécute via `ant`** — `ant dist`
pour le jar, `ant test` pour la suite. Jamais avec un `javac`/`java -cp`
fabriqué à la main, même « juste pour aller vite ».

La raison n'est pas le confort : `clide.jar` **porte des ressources dont le code
dépend à l'exécution**, et un lancement depuis `build/classes` ne les a pas.
Deux pannes observées, toutes deux silencieuses — rien ne dit « il te manque une
ressource », le comportement change simplement :

- `JunitVendorJars` lit `resource/vendor-junit/*.jar` depuis le classpath pour
  les déposer dans le `.clide/tmp/jar-junit/` du projet cible. Hors du jar,
  `getResourceAsStream()` rend `null`, rien n'est extrait, et jdtls ne résout
  plus JUnit **dans le projet ouvert** : `rebuild` rapporte une vague de
  `The import org.junit.platform.launcher cannot be resolved` qui n'a rien à
  voir avec le code qu'on vient d'écrire. Sur clide lui-même : 30 erreurs
  fantômes, qui disparaissent en relançant le même code depuis le jar.
- La suite de tests lancée avec un classpath assemblé à la main perd des jars
  de `lib/` que `test.runtime.classpath` inclut (voir `build.xml`), et rend des
  échecs qui n'existent pas sous `ant test`.

Le piège commun aux deux : la panne ressemble à un bug du code testé. On
diagnostique alors le mauvais objet — et une conclusion tirée d'un lancement
hors `ant` ne vaut rien, y compris quand elle est rassurante.

## Style

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
  - **Exception : les patterns `instanceof`.** `if (x instanceof Truc t == false)`
    compile, mais ne rend *pas* `t` visible dans la suite de la méthode : la
    portée de flux d'une variable de motif n'est définie par le JLS que pour
    `!`, `&&`, `||` et `?:`, jamais pour `== false`. Écrire la forme
    positive — `if (x instanceof Truc t) { ... }`, le cas de repli après le
    bloc — plutôt que de retomber sur `!`.
- **Le `hint` d'un `CommandResult.error()` est facultatif, et le défaut est de
  ne pas en mettre.** Un hint n'a le droit de dire que trois choses :
  - un **état calculé à l'exécution**, que l'appelant ne peut pas reconstituer
    (`check that /home/foo/projet/.classpath declares a test source folder`,
    `the cap stays at 100`) ;
  - un **lien causal contre-intuitif**, qui réoriente le diagnostic (« un run
    vide est bien plus souvent un mauvais sélecteur ou un rebuild manquant
    qu'un projet sans tests ») ;
  - une **commande suivante déjà remplie**, à copier telle quelle
    (`find_symbol calculer`).

  Deux formes sont interdites. Le **pointeur vers la doc** (« run help… »,
  « voir man X ») : le client a déjà `help`, `man` et `CLAUDE.md`, et le lui
  répéter ne fait que coûter des tokens. Et surtout la **supposition** : un
  hint faux est pire que pas de hint, parce qu'un agent le suit — il paie la
  piste suggérée, reste bloqué, et croit désormais avoir éliminé la bonne
  hypothèse. Si la cause n'est pas connue, le message seul suffit.

  Test à s'appliquer avant d'en écrire un : *est-ce dérivable du message plus
  la doc que l'appelant a déjà ?* Si oui, ce n'est pas un hint, c'est du bruit.
  Si le hint ne fait que reformuler le message à l'impératif, c'est que sa
  place est dans le message.
