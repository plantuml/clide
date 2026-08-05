# JCODEMUNCH.md — Idées à reprendre de jcodemunch-mcp

Analyse (2026-08-05) de https://github.com/jgravelle/jcodemunch-mcp :
un serveur MCP Python (~91 outils) d'exploration de code « token-efficient »
pour agents IA. Principe : indexer une fois le dépôt via tree-sitter
(AST → symboles avec offsets d'octets, persistés en SQLite), puis servir
des symboles exacts à la demande au lieu de fichiers entiers. Même exercice
que `JAVALENSE.md` : ce document liste les idées qui valent d'être
reprises, par ordre d'intérêt décroissant pour clide.

## Différence d'architecture fondamentale — et pourquoi c'est complémentaire

jCodeMunch et clide ne jouent pas dans la même cour, et c'est documenté
chez eux avec une candeur rare :

- **jCodeMunch est un index syntaxique, pas un moteur sémantique.**
  tree-sitter parse sans résoudre : pas de bindings, pas de classpath, pas
  de distinction entre deux méthodes homonymes de types différents. Là où
  clide répond « compiler-accurate » via jdtls, jCodeMunch répond « vite et
  pas cher, avec un score de confiance ». Leur `find_references` est un
  matching d'identifiants amélioré ; il devient « compiler-verified »
  seulement en *important* un fichier SCIP produit par un vrai compilateur
  (leur charte : « jCodeMunch never runs your compiler, tests, or code »).
- **Ce qu'ils refusent par charte est exactement ce que clide priorise.**
  Compiler et obtenir les erreurs exactes (priorité n°1), lancer un test
  ciblé (n°2) : hors périmètre chez eux, cœur de clide chez nous. À
  l'inverse, leur terrain (résumés budgétés, métriques d'architecture,
  scoring de pertinence) n'est pas le nôtre.
- **Les 91 outils ne sont pas un modèle à suivre.** Une part importante de
  leur ingénierie (tiers d'outils `core`/`standard`/`full`, façade
  « Counter » à 3 outils, budget CI de 4 000 tokens sur les schémas,
  moratoire sur les nouveaux outils faute de routage fiable à 45,8 % de
  rappel) sert à *gérer les conséquences* d'une surface pléthorique. clide,
  avec ~20 commandes et un protocole texte minimal, n'a simplement pas ce
  problème — le garder ainsi est la leçon.

Ce qui se transpose n'est donc pas leur technologie de parsing mais leur
**discipline d'interaction avec l'agent** : fraîcheur déclarée, preuve
d'absence, guidage anti-grep, échelle de granularité. Détail ci-dessous.

## 1. La fraîcheur déclarée dans chaque réponse (leur meilleure idée pour nous)

C'est notre angle mort connu (`JAVALENSE.md` idée n°1, repris dans
`LUA.md` « Staleness ») : Claude édite hors de clide, le modèle jdtls
périme silencieusement, et `find_*`/`hover` répondent sur un état ancien
sans aucun signal. javalens-mcp répond par la **réparation systématique**
(vérifier le disque avant chaque requête). jCodeMunch ajoute deux pièces
différentes et complémentaires :

- **Déclarer l'état plutôt que (seulement) le réparer.** Chaque réponse
  porte une fraîcheur explicite à quatre états — `fresh` /
  `edited_uncommitted` / `stale_index` / `unknown` — avec une règle dure :
  le classifieur ne doit **jamais** répondre `fresh` pour une comparaison
  qu'il n'a pas pu faire (`unknown` ≠ `fresh`). Transposé à clide : tant
  que la vérification disque systématique n'existe pas, un simple marqueur
  `!WARNING STALE_MODEL: N fichier(s) modifié(s) depuis le dernier rebuild`
  sur les réponses `find_*`/`hover`/`print_diagnostics` transformerait le
  danger silencieux en signal — pour un coût dérisoire (comparer les mtime
  du snapshot déjà tenu par `refreshChangedFiles()`, sans rebuild). C'est
  une étape intermédiaire honnête avant le « vérifier-réparer avant chaque
  requête » façon javalens, et elle reste utile même après (pour le cas
  `rebuild` en cours, cf. leur état `degraded`).
- **Le hook post-édition plutôt que la discipline de l'agent.** Leur
  parade principale n'est pas dans le serveur mais dans le client : un
  hook Claude Code `PostToolUse` sur `Edit|Write` relance l'indexation du
  fichier touché en arrière-plan, systématiquement. La boucle de l'agent
  redevient « éditer → interroger », sans « rebuild à ne pas oublier ».
  Transposé : un hook qui déclenche `clide rebuild` (ou, moins cher, qui
  marque le modèle comme périmé pour le warning ci-dessus) après tout
  Edit/Write dans le projet ouvert. À noter : ça vit dans la configuration
  du *projet analysé* (côté Claude), pas dans clide — un `scripts/` ou une
  section du CLAUDE.md du projet cible suffirait.

Les deux se combinent : le hook rend la staleness rare, le warning la rend
visible quand elle survient quand même (hook non installé, édition par un
autre canal). Et le mode script Lua (`LUA.md`) — où la staleness est la
plus dangereuse — bénéficierait des deux sans logique nouvelle.

## 2. « Trouver zéro » comme réponse prouvée, pas comme absence de résultat

clide a déjà le principe (« Finding nothing is not an error », CLAUDE.md ;
le résultat vide de `find_symbol` qui précise que les champs sont hors
de portée). jCodeMunch pousse l'idée beaucoup plus loin, et leur
formulation mérite d'être citée : *« 'I never learned that file' and
'that file does not exist' look identical to every agent downstream »*.
Trois mécanismes chez eux :

- **Le verdict de recherche** : `ok` / `low_confidence` / `absent` /
  `degraded`. `absent` est une *affirmation* portée par des comptes (« N
  fichiers scannés, index génération G ») ; `degraded` signifie « l'index
  est impairé, le silence ne prouve rien » — et dans cet état le serveur
  **refuse d'affirmer l'absence**.
- **Le contrat de couverture** : une affirmation d'absence énumère ce que
  le corpus excluait à l'indexation (extensions non supportées, fichiers
  trop gros, binaires…). Un scan complet d'un corpus incomplet n'est pas
  une preuve complète.
- **La guidance comportementale associée**, dans leur politique CLAUDE.md :
  sur un verdict d'absence, *ne pas relancer la recherche avec d'autres
  termes*, *ne pas supposer qu'un fichier voisin implémente la
  fonctionnalité*, mais rapporter « à créer ». Ils ont observé que sans
  cette consigne, l'agent tâtonne.

Transposition clide, à petite échelle : quand `find_reference`/
`find_implementation`/`search_regex` rendent zéro, la phrase de réponse
peut porter le périmètre réellement couvert (« 0 référence — modèle du
build de HH:MM, 3601 fichiers ») ; combiné avec le `STALE_MODEL` de
l'idée n°1, « zéro sur modèle frais » et « zéro sur modèle périmé »
deviennent deux réponses différentes — aujourd'hui indiscernables. Et les
pages `man` des `find_*` gagneraient un paragraphe « que conclure d'un
résultat vide » (rejoint l'idée n°8 de `JAVALENSE.md` sur la guidance,
mais avec ce contenu précis, qui manquait).

## 3. L'aide générée par l'outil lui-même, et l'amorce en une ligne

Leur mécanisme d'onboarding est remarquablement économe : le CLAUDE.md du
projet analysé contient **une seule ligne** — « Call the jcodemunch_guide
tool and strictly follow its instructions » — et l'outil `jcodemunch_guide`
renvoie la politique d'usage complète, **générée à l'exécution** depuis la
version réellement installée, filtrée aux outils réellement actifs (ils ont
eu le bug inverse : une doc statique qui nommait 25 outils sur un serveur
qui en exposait 6). La doc ne peut pas dériver du code, parce qu'elle en
sort.

clide a déjà les deux briques : `help`/`man` générés par réflexion sur
`CommandRegistry` (jamais de doc à la main par commande), et le constat
`JAVALENSE.md` n°8 que le biais d'entraînement vers grep est réel.
Ce qui manque est l'assemblage : une commande (ou une section de `help`)
qui donne la *politique* — quand préférer `find_reference` à
`search_regex`, toujours `rebuild` après édition externe, que faire d'un
résultat vide — de sorte que le CLAUDE.md d'un projet analysé par clide
se réduise à « lance `clide <projet>` puis suis `help` ». Aujourd'hui
cette politique vit dans le CLAUDE.md de clide lui-même, que l'agent
travaillant sur *PlantUML* n'a aucune raison d'avoir lu.

Dans la même veine, leur hook `PreToolUse` sur `Read|Grep` (un
avertissement — pas un blocage — quand l'agent s'apprête à lire un gros
fichier ou grepper un arbre indexé, suggérant l'outil sémantique
équivalent) est la parade *active* au biais grep, là où la politique
CLAUDE.md est la parade *déclarative*. À garder en tête le jour où le
biais s'observe en pratique avec clide.

## 4. L'échelle de granularité — validation directe de `summarize_package`

Leur enseignement le plus opérationnel : l'essentiel des économies vient
d'une **échelle de vues** explicite, du plus grossier au plus fin —
outline du dépôt → carte budgétée signatures-seules → outline de fichier
(signatures + résumés, jamais de corps) → source exacte d'un symbole →
fichier entier en **dernier recours** (leur politique le dit dans ces
termes). L'agent descend l'échelle au besoin, et leur consigne est
« avant d'ouvrir un fichier, demande d'abord son outline ».

C'est une validation directe du `summarize_package` déjà décidé dans
`TODO.md`, et de ses choix ouverts :

- **Signatures + première ligne de Javadoc** plutôt que signatures nues :
  c'est leur format standard (`signature` + `summary` d'une ligne), et
  c'est le niveau qu'ils décrivent comme le cheval de trait de
  l'exploration. La question ouverte de `TODO.md` (« Javadoc : alourdit ou
  éclaire ? ») a chez eux une réponse d'expérience : la ligne de résumé
  paie.
- **Un étage au-dessus manque encore chez clide** : leur `get_repo_outline`
  (« quels packages, quelles distributions de types, où est le centre de
  gravité ») est la vue d'entrée en terrain inconnu. Un
  `summarize_project` — la liste des packages avec leurs comptes de
  types — serait le pendant naturel au-dessus de `summarize_package`,
  pour le même besoin (« par où commencer ») que jdtls ne couvre pas.
- À l'inverse, leur raffinement `detail_level=compact/standard/full` par
  commande est une complexité que ~20 commandes ne justifient pas — le
  `set_max_results` de session plus des formes de sortie fixes et bien
  choisies suffisent.

## 5. Verdicts de préflight pour les futures commandes d'écriture

Leurs outils de sécurité pré-édition rendent des **verdicts nommés**, pas
des booléens : `check_delete_safe` répond dans un vocabulaire de sept
états (`safe_to_delete` / `test_coverage_only` / `internal_only` /
`internal_uses_blocking` / `external_uses_blocking` / `cross_repo_blocking`
/ `entry_point`), avec les bloqueurs classés et une action recommandée.
C'est exactement la culture déjà en place dans clide (les codes d'erreur
nommés sur lesquels un appelant branche), appliquée à une question de
préflight plutôt qu'à un échec.

Le jour où `change_signature` existe (TODO.md), les deux risques
sémantiques déjà identifiés — réordonner deux paramètres de même type,
supprimer un paramètre dont l'expression a un effet de bord au site
d'appel — sont précisément le genre de choses qu'un préflight devrait
*nommer* avant d'appliquer : un `check_signature_change` qui répond
`SAME_TYPE_PARAMS_REORDERED: la recompilation ne détectera pas un site
d'appel non mis à jour` transforme un piège documenté en garde-fou
outillé. Même logique pour un futur `replace_symbol` (`LUA.md`) : le
couple `diff_transaction`/`commit_transaction` donne déjà le
« proposer avant d'appliquer » ; le verdict nommé donne le « prévenir
avant de proposer ».

## 6. La notation stable de symbole — leur pratique valide SYMBOLS.md niveaux 2-3

Leur identifiant de symbole est `chemin::nom.qualifié#kind`
(`src/main.py::MyClass.login#method`, surcharges désambiguïsées par
suffixe `~1`, `~2`) : stable à travers les éditions tant que le symbole
existe, contrairement à une position ligne:colonne. C'est, à la syntaxe
près, le niveau 3 de l'échelle spécifiée dans `SYMBOLS.md`
(`Classe::membre`, arité optionnelle) — dont l'implémentation était
différée. Leur expérience confirme les deux moitiés du design clide :

- l'identifiant indépendant de la ligne est ce qui survit à une session
  d'édition (chez eux c'est la *seule* forme, et ça tient) ;
- la position exacte reste nécessaire en complément (leur
  `get_symbol_source` retourne lignes et offsets ; notre
  `chemin:ligne:colonne:nom` reste le filet de sécurité et le format des
  résultats).

Rien à copier techniquement — plutôt une confirmation que monter le
niveau 3 de `SYMBOLS.md` dans la pile des chantiers a du sens, en
particulier pour le mode Lua où faire circuler un identifiant stable
entre commandes (piège déjà noté dans `LUA.md` : « faire circuler un
identifiant stable plutôt que re-matcher par nom à chaque appel »).

## 7. Divers — culture de mesure et de doc, en passant

Trois pratiques de leur dépôt, petites et transposables telles quelles :

- **« Never hand-type a benchmark number. »** Tout chiffre publié sort
  d'un artefact commité que la CI re-exécute et compare ; un chiffre
  recopié à la main fait échouer le build. Les mesures de clide
  (`rebuild` 9-12 s, balayage AST 22,3 s, reparse 77 ms…) vivent en prose
  dans les .md — au premier changement de machine ou de version elles
  dériveront sans bruit. À notre échelle, la version minimale est de
  dater/contextualiser chaque chiffre (déjà fait en général) et de les
  regrouper pour re-mesure périodique, plutôt qu'une CI dédiée.
- **`measured` vs `declared`.** Chaque constante de confiance chez eux est
  étiquetée : mesurée (artefact à l'appui) ou déclarée (prior d'ingénierie,
  assumé). Les .md de clide font déjà cette distinction implicitement
  (« mesure unique », « pas encore vus en vrai » dans JDTLS.md) — la
  rendre systématique coûte peu.
- **Leur discipline d'issues** rejoint la nôtre mais deux formules valent
  d'être volées : *« une release n'est jamais bloquée par une issue
  ouverte »* et *« un timebox sans action par défaut est un vœu »*.

## Ce qu'on ne reprend délibérément pas

- **L'encodage compact MUNCH** (format ligne-orienté avec légende et
  tables CSV, ~45 % d'octets gagnés sur du JSON) : il résout un problème
  de *réponses JSON verbeuses* que le protocole texte de clide n'a pas —
  nos réponses sont déjà la forme la plus courte qui se recopie dans la
  commande suivante.
- **Tiers d'outils, façade Counter, budget de schémas** : pathologies
  d'une surface à 91 outils. La prophylaxie pour clide est de ne pas y
  arriver.
- **Embeddings, PageRank, scoring de pertinence, poids appris** : jCM en a
  besoin parce que son index approxime ; jdtls répond exactement. Le jour
  où un besoin de « cold start » se fait sentir, `summarize_project`/
  `summarize_package` (idée n°4) y répondent structurellement, sans
  ranking.
- **Les reçus d'évidence cryptographiques** (`sha256`, resources
  immuables, producteurs enregistrés) : la version proportionnée pour
  clide est l'idée n°2 (périmètre et fraîcheur déclarés en clair dans la
  réponse), pas l'appareil de preuve.
- **Le compteur de tokens économisés** et son marketing.

## Résumé des chantiers suggérés, par coût croissant

1. Enrichir les réponses vides des `find_*` avec le périmètre couvert, et
   les pages `man` avec « que conclure d'un résultat vide » (idée n°2) —
   texte seulement.
2. `!WARNING STALE_MODEL` sur les commandes sémantiques quand des mtimes
   ont bougé depuis le dernier build (idée n°1) — la brique
   `refreshChangedFiles()` existe.
3. Une sortie « politique d'usage » générée (extension de `help`/`man`,
   idée n°3), pour que le CLAUDE.md d'un projet analysé tienne en une
   ligne.
4. `summarize_package` tel que décidé dans TODO.md, avec résumé d'une
   ligne par membre, plus un `summarize_project` au-dessus (idée n°4).
5. Hook post-édition côté projet analysé (idée n°1, seconde moitié).
6. Verdicts de préflight nommés, à concevoir avec les premières commandes
   d'écriture (idée n°5) — pas avant elles.
