# AGENT-TESTING.md — consignes pour l'agent chargé de tester clide

Ce fichier s'adresse à un **agent** (pas à un humain) à qui l'on confie une
campagne de test de `clide`. Il décrit ce qu'il faut installer, comment
construire l'outil **avec Ant**, sur quoi le tester, comment le tester, et
sous quelle forme rendre compte.

Le compte rendu est le livrable : il doit être écrit dans un fichier
**`AGENT-TESTING-RESULT.md`** à la racine du dépôt clide (voir la dernière
section, « Le livrable »).

Lecture préalable obligatoire, dans cet ordre :

1. `CLAUDE.md` — comment utiliser clide aujourd'hui (commandes, protocole,
   notation `<position>`, limites connues). C'est le contrat que la campagne
   doit vérifier.
2. `TESTS.md` — journal des campagnes précédentes (C1 à C5) et surtout le
   tableau **« État des points »** en bas : la liste numérotée des problèmes
   ouverts/corrigés. La campagne s'inscrit dans cette continuité.
3. `TODO.md` — le backlog, pour ne pas rapporter comme « manque » ce qui est
   déjà décidé et en attente d'implémentation.
4. `RESULTS.md` — seulement si tu veux parser finement les sorties.
   `CODING.md` seulement si tu touches au source de clide.

---

## 1. Objectif de la campagne

clide est un « Command Line IDE » : un daemon qui pilote **Eclipse JDT
Language Server (jdtls)** en ligne de commande, pour donner à un agent ce
qu'un grep ne peut pas donner — compiler et obtenir les erreurs exactes,
lancer un test ciblé, poser des questions sémantiques (qui appelle quoi, qui
implémente quoi).

Tu dois tester clide **sur deux cibles** :

- **clide lui-même** (`https://github.com/plantuml/clide`) — petit projet,
  boucle rapide, et cas particulier intéressant : c'est un projet
  « auto-hébergé » qui peut posséder ses propres fichiers Eclipse (voir §6.1).
- **PlantUML** (`https://github.com/plantuml/plantuml`) — ~3600 fichiers Java,
  la vraie cible de référence de toutes les campagnes précédentes. C'est là
  que les problèmes d'échelle apparaissent.

Ce qui est attendu de toi n'est pas de dérouler une checklist : c'est de
**vérifier que clide dit vrai**. Une commande qui répond n'est pas une
commande qui a raison.

---

## 2. Environnement

Requis :

- **JDK 21** (`java -version` doit afficher 21.x). jdtls et le build en
  dépendent.
- **Apache Ant**. S'il n'est pas présent :
  `apt-get install -y ant` (vérifié : Ant 1.10.14 suffit).
- `git`, `unzip`.
- **Aucun accès réseau n'est nécessaire pour construire clide** — et il ne
  faut pas en supposer un : toutes les dépendances sont des `.jar` commités
  sous `lib/`, et jdtls est commité sous forme d'archive à la racine.

À noter avant de commencer : dans le sandbox, la variable d'environnement
**`JAVA_TOOL_OPTIONS` est positionnée**. Chaque JVM lancée écrit donc une
ligne `Picked up JAVA_TOOL_OPTIONS: …` sur stderr. C'est la cause directe du
point 17 de `TESTS.md` — **ne filtre pas cette ligne à l'aveugle** avec un
`grep -v`, tu masquerais des messages utiles (la campagne 5 s'est trompée de
diagnostic exactement comme ça).

---

## 3. Checkout des deux dépôts

Tu es autorisé — et invité — à cloner PlantUML toi-même :

```bash
cd /tmp
git clone --depth 1 https://github.com/plantuml/clide.git
git clone --depth 1 https://github.com/plantuml/plantuml.git
```

Notes :

- `--depth 1` suffit ; les dépôts sont volumineux (`clide` embarque
  `jdt-language-server-latest.zip`, ~49 Mo).
- Si une branche particulière t'est indiquée pour PlantUML (les campagnes
  passées ont travaillé sur une branche `clide`), utilise-la ; sinon la
  branche par défaut convient et c'est le cas nominal à tester.
- Travaille sur des clones **jetables** dans `/tmp`. Tu ne dois rien pousser,
  rien committer, sur aucun des deux dépôts.

---

## 4. Build de clide **avec Ant**

### 4.1 Les commandes

```bash
cd /tmp/clide
ant dist      # cible par défaut : produit ./clide.jar (fat jar, ~56 Mo)
ant test      # exécute les tests unitaires de clide
ant help      # liste les cibles et les options (-Dtest=<FQCN>, etc.)
ant clean     # supprime build/ et clide.jar
```

Mesures relevées au moment d'écrire ce fichier, sur un sandbox Linux/JDK 21 :
`ant dist` ≈ **9 s** (aucun accès réseau), `clide.jar` = **56 Mo** ;
`ant test` ≈ **11 s**, **435 tests, 0 échec**. Si tes chiffres s'écartent
franchement de ceux-là, c'est déjà un constat à noter.

`ant dist` est la cible par défaut : `ant` tout court fait la même chose.

### 4.2 Ce que `ant dist` produit, et pourquoi ça compte

Le jar est **autoportant** : il n'a besoin de rien à côté de lui sur le
disque. Il embarque, sous un répertoire de premier niveau `resource/` :

- `resource/jdt-language-server-latest.zip` — jdtls lui-même, que
  `JdtlsLauncher` extrait tout seul au premier démarrage ;
- `resource/vendor-junit/*.jar` — les jars JUnit que `JunitVendorJars` copie
  dans `.clide/tmp/jar-junit/` du **projet cible**, pour que jdtls sache
  *compiler* ses sources de test même si ce projet n'embarque aucun JUnit
  (c'est ce qui a réglé le point 14 : 6058 erreurs de compilation sur
  PlantUML, rapportées à tort comme « aucun test trouvé »).

Vérifie-le si tu veux en avoir le cœur net :

```bash
unzip -l clide.jar | grep -E "resource/jdt|resource/vendor-junit"
```

Si l'une de ces entrées manque, tu n'as pas un jar utilisable — et c'est un
problème de build à rapporter, pas un problème d'exécution à contourner.

### 4.3 Lance toujours **le jar**, jamais les classes

Corollaire direct de ce qui précède, et piège dans lequel on tombe vite parce
qu'il paraît anodin : `java -cp build/classes:lib/* clide.Main …` démarre,
répond aux commandes, et **n'a aucune des ressources ci-dessus**.
`getResourceAsStream("resource/vendor-junit/…")` rend `null`, rien n'est
extrait dans le projet cible, et jdtls n'y résout plus JUnit. Symptôme :

```
[error] line 14: The import org.junit.platform.launcher cannot be resolved
```

…par dizaines, sur des fichiers auxquels personne n'a touché. Sur clide
lui-même, 30 erreurs de ce genre, qui s'évaporent en relançant exactement le
même code avec `java -jar clide.jar`.

Ce qui rend ce piège coûteux n'est pas la panne, c'est le diagnostic : rien
n'annonce une ressource manquante, donc on conclut que le **projet cible** a un
problème de classpath, et on va « corriger » son `.clide/` en y copiant des
jars dont il n'a aucun besoin. Si un `rebuild` sort des erreurs d'import sur
des bibliothèques que le projet n'a pas touchées, vérifie d'abord **comment tu
as lancé clide**, avant de toucher au projet.

Même règle pour la suite de tests : `ant test`, jamais un
`junit-platform-console-standalone` invoqué avec un classpath assemblé à la
main — il rend des échecs qui n'existent pas sous `ant test` (voir `CODING.md`,
« Construire et vérifier »).

### 4.4 Où jdtls est extrait — et ce qu'il faut vérifier

jdtls s'extrait tout seul au premier démarrage, dans un **cache utilisateur
partagé** nommé d'après l'empreinte de l'archive :
`~/.cache/clide/jdtls-<crc>` (`$XDG_CACHE_HOME/clide/…` si la variable est
posée, `%LOCALAPPDATA%\clide\…` sur Windows). Le daemon annonce le chemin
qu'il a résolu :

```
(2/4) Initializing IDE ... [OK] (jdtls: /home/foo/.cache/clide/jdtls-ba495e18)
```

Ni le projet ouvert ni le répertoire courant ne reçoivent quoi que ce soit —
c'était le point 19 de `TESTS.md`, corrigé. **Vérifie-le** : lance clide
depuis un répertoire quelconque et contrôle qu'il reste vide, et que le
cache ne contient qu'un seul répertoire `jdtls-*` même après plusieurs
lancements depuis des endroits différents.

Deux comportements à contrôler au passage :

- **`CLIDE_JDTLS_HOME`** est utilisé tel quel, sans suffixe d'empreinte.
- **L'empreinte invalide bien le cache** : un `clide.jar` reconstruit autour
  d'une archive différente doit résoudre vers un **nouveau** répertoire,
  jamais réutiliser l'ancien.

Prends malgré tout un répertoire de travail neutre (`/tmp/run`) pour toute la
campagne : ça isole les éventuels résidus d'un autre bug de ceux du projet
testé.

### 4.5 Vérification que le build est sain

```bash
mkdir -p /tmp/demo/src/main/java/demo
printf 'package demo;\npublic class Square {\n\tpublic double area() { return 4; }\n}\n' \
  > /tmp/demo/src/main/java/demo/Square.java
cd /tmp/run
nohup java -jar /tmp/clide/clide.jar /tmp/demo > /tmp/demo-daemon.log 2>&1 &
# attends "Daemon ready on port ..." dans /tmp/demo-daemon.log avant de continuer
printf 'help\nexit\n' | python3 /tmp/clide/clide.py /tmp/demo
```

Le daemon (premier bloc) doit afficher les 4 étapes de démarrage (`(1/4)` …
`(4/4) Building project … [OK]`) puis `Daemon ready on port …`. Démarrage à
froid mesuré : ~22 s sur ce mini-projet (extraction de jdtls comprise), bien
plus sur PlantUML — un coût payé une seule fois, pas à chaque connexion.
Chaque appel suivant du client (`python3 clide.py /tmp/demo`) contre ce même
daemon doit ensuite afficher la bannière de connexion puis la liste des
commandes, en quelques dizaines de ms.

Si `help` répond, le socle est bon.

---

## 5. Comment parler à clide

Trois règles, à intégrer avant le premier test — la plupart des faux
« bugs » des campagnes passées venaient de là :

1. **Un token par ligne.** Le mot-clé seul sur la première ligne, puis chaque
   paramètre sur sa propre ligne. `print_diagnostics errors` sur une seule
   ligne échoue en `?ERROR UNKNOWN_KEYWORD` — ce n'est pas un bug.
2. **Toujours finir par `exit`.** Une entrée qui s'arrête au milieu d'une
   commande donne `?ERROR MISSING_PARAMETERS` et la connexion tombe.
3. **Pas de quotes.** La ligne *est* la valeur (trimmée) ; des guillemets
   finiraient dans la valeur.

Forme typique d'un test (le daemon pour `/tmp/plantuml` doit déjà tourner —
voir 4.5) :

```bash
printf 'find_reference\nmethod\nsrc/main/java/…/Foo.java:42:17:bar\nexit\n' \
  | python3 /tmp/clide/clide.py /tmp/plantuml
```

Utilise `help` (liste + arité de chaque commande) et **`man <commande>`**
(page détaillée, sections ERRORS / SEE ALSO) : ils sont intégrés et font
autorité sur `CLAUDE.md` en cas de désaccord — un désaccord entre les deux
étant lui-même un résultat à rapporter. Démarrer le daemon avec `--human`
(`java -jar clide.jar --human <projet>`) active les prompts `> READY` /
`> <paramètre> ?` pour toute la durée de vie de ce daemon, utile pour
explorer à la main, à éviter en pipe — ça ne se choisit plus par connexion.

Fin de session : `exit` (le daemon survit), `terminate` (arrête le daemon).

---

## 6. Ce qu'il faut tester

### 6.1 Sur clide lui-même

Ouvre clide **sur le checkout de clide** — démarre d'abord le daemon, puis
connecte le client :

```bash
nohup java -jar /tmp/clide/clide.jar /tmp/clide > /tmp/clide-daemon.log 2>&1 &
# attends "Daemon ready on port ..." dans /tmp/clide-daemon.log, puis :
cd /tmp/run && python3 /tmp/clide/clide.py /tmp/clide
```

Intérêt : boucle courte, code que tu viens de lire, et cas limite connu — un
projet qui possède déjà ses propres `.project`/`.classpath` peut déclencher
`?ERROR MULTI_MODULE_PROJECT` (« this repository holds N modules ») sur
`run_test`/`run_tests`, parce que les fichiers d'origine déplacés sous
`.clide/tmp/` sont ré-importés par jdtls comme un second projet. Vérifie si
ça se produit ici, et dis-le.

À couvrir au minimum : `rebuild`, `print_diagnostics`, `find_symbol`,
`find_reference`, `find_declaration`, `find_implementation`, `hover`,
`list_members`, `search_regex`, `run_test`, `run_tests`, `set_max_results`,
`help`, `man`, `exit`, `terminate`.

Compare aussi **`run_tests` de clide via clide** avec `ant test` : les deux
doivent trouver les mêmes tests et les mêmes résultats (435 tests, 0 échec au
moment d'écrire ces lignes). Un écart est un vrai signal — soit sur la
découverte des tests, soit sur leur exécution.

### 6.2 Sur PlantUML

C'est la cible principale. Le protocole des campagnes précédentes, à
reprendre et à étendre :

- **Build initial** : nombre de fichiers, erreurs, warnings, durée.
- **`rebuild`** : à vide (baseline), puis après une erreur volontaire
  introduite avec tes propres outils d'édition (jamais via clide — aucune
  commande n'édite aujourd'hui). Vérifie fichier + ligne + message exacts, et
  que le **modèle sémantique** est bien rafraîchi (ajoute une méthode, puis
  `find_symbol` dessus après `rebuild`).
- **Le scénario « refactor incomplet »**, le plus révélateur : change la
  signature d'une méthode d'interface très implémentée, `rebuild`, et vérifie
  que toutes les implémentations manquantes *et* tous les sites d'appel
  remontent — classes internes et anonymes de test comprises.
- **`run_test`** au niveau **classe** et au niveau **méthode** (le second est
  cassé sur les méthodes à paramètres, point 16 — confirme ou infirme).
- **`run_tests`** sur toute la suite : durée, totaux, et décomposition
  honnête des échecs (environnementaux vs réels).
- **Requêtes sémantiques contre-vérifiées** — voir §7.

⚠️ **`run_tests` sur PlantUML réécrit des fichiers** de
`src/test/resources/vega/`. C'est PlantUML qui le fait, pas clide, mais
vérifie l'état de `git status` avant/après et signale-le.

### 6.3 Tests libres

Au-delà de ce qui précède, tu es **explicitement encouragé à improviser** à
partir des fonctionnalités que `help` et `man` t'exposent. Deux formes qui
ont bien marché :

- **Missions de navigation en carte blanche** : pose-toi une vraie question
  d'architecture sur PlantUML (« comment le type de diagramme est-il
  choisi ? », « qui décide de la police par défaut ? ») et résous-la
  uniquement avec clide. Compte les commandes nécessaires, et note si le
  résultat d'une commande se recolle sans retouche dans la suivante — c'est
  le point de design central de l'outil.
- **Cas limites du protocole** : ligne hors bornes, nom absent de la ligne,
  mauvaise colonne, fichier inexistant, position périmée après édition,
  `set_max_results 0`, valeur > 10000, `terminate` avec quelque chose
  d'ouvert. Vérifie que chaque échec est **propre, nommé (`?ERROR <CODE>`) et
  rapide** — un échec lent ou muet est un bug à part entière.

Ne te limite pas à cette liste. Une idée de test qui n'y figure pas et qui
trouve quelque chose vaut mieux que trois cases cochées.

### 6.4 Points ouverts à revérifier

Le tableau « État des points » de `TESTS.md` fait foi. Au moment d'écrire ce
fichier, restent ouverts et méritent une vérification explicite :

| # | À vérifier |
|---|---|
| 5 | `find_declaration type` vers un type JDK (`String`) : toujours 30 s de timeout ? Message clair ? |
| 6 | `man` affiche-t-il encore « please write help of man » dans `help` ? |
| 9, 10, 11 | Call hierarchy, type hierarchy structurée, membres hérités : toujours absents ? |
| 13 | `rebuild` sans changement paie-t-il encore le build complet ? |
| 16 | `run_test` sur une méthode à paramètres (`@ParameterizedTest`) — **priorité 1** |
| 17 | Le message d'échec de `run_test` est-il encore mangé par `Picked up JAVA_TOOL_OPTIONS` ? |
| 18 | Un test `ABORTED` (assumption non satisfaite) est-il encore compté `failed` au lieu de `skipped` ? |
| 19 | `jdtls/` (62 Mo) déposé dans le CWD — **corrigé**, à revalider (voir §4.4) |
| 20 | Commandes de transaction documentées dans `CLAUDE.md` mais absentes de `help` |
| 21 | Chiffres périmés de `CLAUDE.md` (coût de `rebuild`, moment où `.project` est retiré) |

Un point que tu ne peux pas tester doit être rapporté comme **non testé**,
pas comme corrigé.

---

## 7. Méthode : contre-vérifier, ne pas constater

La règle qui a produit les meilleurs résultats des campagnes précédentes :
**diffe systématiquement les réponses de clide avec un `grep`, dans les deux
sens.**

- Ce que `grep` remonte et que clide exclut → clide a-t-il raison ? (les
  écarts trouvés jusqu'ici étaient tous des faux positifs de `grep` : lignes
  commentées, homonymie sur une classe qui n'implémente pas l'interface, la
  déclaration elle-même.)
- Ce que clide remonte et que `grep` rate → l'override générique, la classe
  anonyme dans un test. C'est là que se trouve la valeur de l'outil, et ça ne
  se voit pas si tu ne cherches que dans un sens.

Trois disciplines associées :

- **Mesure ce que tu affirmes.** Durées, comptes, tailles : donne des
  chiffres, pas des adjectifs. Si un chiffre de `CLAUDE.md` est faux,
  dis-le avec ta mesure à côté.
- **Doute de ton propre outillage avant de crier au bug.** Sortie vide ?
  Vérifie que ce n'est pas ton `grep`/`head`/pipe qui l'a mangée. Interroge
  le daemon en socket brut au besoin.
- **Rends tout reproductible.** Chaque problème rapporté doit venir avec les
  commandes exactes pour le rejouer.

---

## 8. Hygiène

- **Ne committe rien, ne pousse rien.** Ni sur clide, ni sur PlantUML.
- Les modifications de source que tu introduis pour un test (erreur
  volontaire, méthode sonde, refactor cassé) doivent être **annulées** avant
  de passer à la suite. `git status` / `git diff` en fin de campagne, et
  rapporte-le.
- À la fin : `terminate` (pas seulement `exit`), puis vérifie qu'il ne reste
  rien à la racine des projets ouverts — `jdtls/`, `bin/`, `.project`,
  `.classpath`, `.clide/tmp/`. `CLAUDE.md` promet qu'un `git status` ne bouge
  jamais à cause de clide : vérifie cette promesse, elle a déjà été prise en
  défaut.
- Tu peux modifier le source de clide **pour isoler un bug** (ajouter une
  trace, tester un correctif d'une ligne) ; dans ce cas dis-le explicitement
  dans le compte rendu, relance `ant dist` pour reconstruire, et distingue
  nettement « comportement observé sur le code d'origine » de « comportement
  après ma modification locale ». Si tu proposes un correctif, décris-le — ne
  le committe pas.

---

## 9. Le livrable : `AGENT-TESTING-RESULT.md`

Écris ton compte rendu dans un **nouveau fichier `AGENT-TESTING-RESULT.md`**
à la racine du dépôt clide. Il doit se suffire à lui-même : quelqu'un qui ne
t'a pas vu travailler doit pouvoir le lire seul et savoir quoi corriger en
premier.

Structure attendue :

1. **En-tête** — date, commit testé (`git rev-parse --short HEAD` sur les deux
   dépôts), version du JDK, version d'Ant, particularités d'environnement
   (`JAVA_TOOL_OPTIONS`, absence de X11, mémoire…).
2. **Mise en place** — ce que le build Ant a demandé, avec les durées et
   tailles mesurées (`ant dist`, `ant test`), et tout écart avec les chiffres
   annoncés au §4.1.
3. **Ce qui marche** — avec les preuves : commandes envoyées, sorties
   obtenues (extraits courts), mesures. Pas de « ça a l'air bon ».
4. **Ce qui ne marche pas** — un problème = un titre numéroté, et pour chacun :
   symptôme observé, commandes pour le reproduire, cause identifiée si tu l'as
   trouvée (avec fichier + ligne du source de clide), impact pour un agent qui
   utilise clide, piste de correction.
5. **Contre-vérifications** — les diffs clide/grep, dans les deux sens, avec le
   verdict pour chaque écart (qui avait raison).
6. **Tests libres** — ce que tu as inventé, ce que ça a donné, y compris les
   pistes qui n'ont rien donné (c'est une information).
7. **Écarts entre la documentation et le code** — tout ce que `CLAUDE.md`
   annonce et que tu n'observes pas, ou l'inverse.
8. **État des points de `TESTS.md`** — pour chaque point encore ouvert :
   *toujours ouvert* / *corrigé* / *non testé*, avec ce qui te le fait dire.
9. **Ordre de correction proposé** — la liste triée, avec en une phrase
   pourquoi ce qui est en tête l'est. C'est la section la plus utile du
   document : ne la bâcle pas.

Deux exigences de fond :

- **Un problème sans reproduction n'est pas un problème.** Chaque constat
  négatif doit être rejouable à partir de ce que tu écris.
- **Distingue ce que tu as observé de ce que tu supposes.** Une hypothèse est
  bienvenue, à condition d'être annoncée comme telle. Une supposition
  présentée comme un fait envoie la correction suivante dans le mur.

Enfin : si la campagne ne trouve rien, dis-le franchement — mais dis aussi
*ce que tu as cherché*. « Rien trouvé » après vingt commandes et « rien
trouvé » après une contre-vérification systématique ne valent pas la même
chose, et seul le second est une information.
