# CODING.md — Conventions de code pour le source de clide

Règles de style pour qui écrit ou modifie le code Java de clide lui-même
(humain ou IA) — sans rapport avec l'utilisation de clide comme outil, voir
`CLAUDE.md` pour ça.

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
