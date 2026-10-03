# Ajout de mesures de performance (profilage JFR) à clide

Ce document résume l'exploration et le prototypage faits pour que **clide**
(`C:\github\clide`, https://github.com/plantuml/clide) mesure les performances
**du projet ouvert** — pas celles de clide lui-même — lors d'un `run_test` /
`run_tests`, et en tire des hotspots exploitables par un agent. Point de départ
pour reprendre le travail dans une nouvelle conversation, sur le modèle de
`JACOCO.md`.

## Contexte : ce qui existe déjà

- `run_test`/`run_tests` passent tous par `ProjectTests.fork()`
  (`src/main/java/clide/test/ProjectTests.java`), qui construit la ligne de
  commande avec `ProjectTests.command()` : `java -ea -cp <prefix+projet+clide>
  clide.test.TestRunnerMain <selector>`. **C'est le seul point à modifier** pour
  ajouter des options `-XX`, un `-javaagent` ou un enregistrement JFR.
- La JVM des tests est celle du daemon (`JdtlsLauncher.javaExecutable()` =
  `java.home`), donc **au moins un JDK 21**, puisque jdtls l'exige. JFR,
  l'API `jdk.jfr.consumer` et l'outil `jfr` sont donc toujours disponibles,
  **sans aucune dépendance à ajouter** (à la différence de JaCoCo).
- `set_test_env` et `set_test_classpath_prefix` (commit `bf893b1`) permettent
  déjà, par session, de piloter l'environnement et le classpath de la JVM des
  tests. Il n'existe pas encore d'équivalent pour les options JVM.
- Le protocole `TestRunnerMain` → `ProjectTests` ne porte **aucune durée par
  test** : seule la ligne `SUMMARY` a un total en ms.
- PlantUML fait déjà ça à la main : `perf.sh` lance le jar avec
  `-XX:StartFlightRecording=…,settings=profile` puis **ouvre le `.jfr` dans
  JMC**. L'objectif est d'offrir la même chose à un agent, en texte.

## Objectif

Offrir un « Mission Control en texte » : un enregistrement JFR fait pendant un
run de test, résumé par clide en tableaux courts, **attribués au code source du
projet** et exprimés en `chemin/relatif.java:ligne Classe.méthode`, pour que
l'agent puisse enchaîner directement `read_lines`, `hover` et `find_callers`
sur un hotspot. Rendre les hotspots navigables sémantiquement, c'est ce que
clide peut apporter de plus qu'un profileur classique.

## Prototype réalisé (bout en bout sur PlantUML, validé dans la sandbox)

1. **Setup sandbox** : `apt-get install ant`. Le JDK de la sandbox est
   OpenJDK 21.0.10 et fournit `jfr`. Maven Central n'est pas joignable (403),
   mais on n'en a pas besoin.
2. **Build de PlantUML** avec `ant dist`, en suivant le `CLAUDE.md` de
   PlantUML. Les jars de test JUnit/XMLUnit/opentest4j sont copiés depuis
   `clide/lib` vers `plantuml/.clide/tmp/jar-junit/`.
3. **Build de clide** avec `ant dist`, puis
   `python3 start_clide.py <plantuml>` : daemon prêt en ~50 s.
4. **Enregistrement JFR sans modifier clide**, en passant les options par
   `JAVA_TOOL_OPTIONS`, que `set_test_env` transmet à la JVM des tests
   (client piloté par stdin, un token par ligne) :
   ```
   set_test_env
   JAVA_TOOL_OPTIONS
   -XX:StartFlightRecording=filename=/…/vega-1ms.jfr,settings=profile,jdk.ExecutionSample#period=1ms,dumponexit=true -XX:FlightRecorderOptions=stackdepth=256
   set_max_results
   0
   run_test
   72b5fbed:src/test/java/test/vega/VegaTest.java:38:7:VegaTest
   ```
   On obtient `run_test: 395 test(s), 391 passed, 0 failed, 4 skipped` et un
   `.jfr` de 5,9 Mo. **Le protocole PASS/FAIL/SUMMARY n'est pas perturbé**,
   même par le message `Picked up JAVA_TOOL_OPTIONS` que la JVM écrit sur
   stderr.
5. **Analyseur prototype** : `scripts/JfrReport.java`, un fichier unique
   lancé directement avec `java` (le lanceur de fichier source du JDK, sans
   compilation préalable), sans dépendance. Il lit le `.jfr` avec
   `jdk.jfr.consumer.RecordingFile` et produit les vues ci-dessous. Il analyse
   8 s d'enregistrement en ~1,5 s.
   ```
   java scripts/JfrReport.java vega-1ms.jfr <plantuml> src/main/java src/test/java \
        --scope src/main/java --top 10
   ```

### Ce que produit l'analyseur (extraits réels, VegaTest complet)

```
== overview
recording     vega-1ms.jfr (8343 ms)
cpu samples   2333  (917 with no in-scope frame: harness/JUnit/JDK-only stacks)
gc            29 collections, 169 ms paused in total, longest 15 ms
allocation    ~845 MB sampled-weight
exceptions    120 thrown

== cpu: inclusive (project method anywhere on the stack)
   1008  43.2%  src/main/java/net/sourceforge/plantuml/SourceStringReader.java  SourceStringReader.outputImage
    897  38.4%  src/main/java/net/sourceforge/plantuml/UgDiagram.java  UgDiagram.exportDiagram
    477  20.4%  src/main/java/net/sourceforge/plantuml/core/TextBlockExporter.java  TextBlockExporter.exportTo

== cpu: hot lines (project line responsible for the sample)
     28   1.2%  src/main/java/net/sourceforge/plantuml/regex/Matcher2.java:154  Matcher2.find
     24   1.0%  src/main/java/net/sourceforge/plantuml/regex/Pattern2.java:114  Pattern2.compileInternal
     19   0.8%  src/main/java/net/sourceforge/plantuml/tim/TrieImpl.java:88  TrieImpl.getOrCreate

== cpu: JDK/library leaf  <-  project caller
      7   0.3%  java.util.HashMap.resize  <-  src/main/java/net/sourceforge/plantuml/tim/TrieImpl.java:88  TrieImpl.getOrCreate
      6   0.3%  java.lang.Character.getType  <-  src/main/java/net/sourceforge/plantuml/text/TLineType.java:287  TLineType.isLetter

== alloc: by project site (bytes, sampled weight)
   63.6 MB   7.5%  src/main/java/net/sourceforge/plantuml/tim/TrieImpl.java:88  TrieImpl.getOrCreate
   26.7 MB   3.2%  src/main/java/net/sourceforge/plantuml/regex/Matcher2.java:60  Matcher2.build
   22.0 MB   2.6%  src/main/java/net/sourceforge/plantuml/tim/TrieImpl.java:44  TrieImpl.<init>
```

Les vues : `overview`, CPU **attribué** (première frame projet sous la
feuille : le code du projet plus ce qu'il appelle dans le JDK), **inclusif**,
**self**, **lignes chaudes**, **feuille JDK ← appelant projet**, allocation
par site projet et par type, contention (`JavaMonitorEnter`/`ThreadPark`).
La définition de « frame projet » est simple : la classe a un `.java` sous une
des racines source, les classes internes étant ramenées à la classe
englobante et les classes cachées (lambdas, `/0x…`) ignorées.

### Ce que le prototype a appris sur PlantUML (constats réels)

- **~30 % du temps CPU de VegaTest est le harnais de test lui-même**, pas
  PlantUML : `test.vega.SvgCleaner.toNormalisedString` (14,9 %) et
  `SvgCleaner.parseXml` (12,8 %), c'est-à-dire la normalisation XML des SVG
  avant comparaison. Sans filtrage, ce sont les deux premiers hotspots. D'où
  l'option `--scope src/main/java`, qui traite le code de test comme une
  bibliothèque. Avec elle, 917 échantillons sur 2333 (39 %) n'ont aucune
  frame dans `src/main`.
- **La construction du registre de fonctions du préprocesseur coûte plus que
  son exécution** : `TContext.<init>` → `addStandardFunctions` représente
  3,3 % du CPU inclusif, contre 3,1 % pour `TContext.executeLines`. Elle
  représente aussi ~100 Mo d'allocations (`TrieImpl.getOrCreate`/`<init>`,
  `FunctionsSet.updateFunctionsByName`). La centaine de fonctions
  standards est ré-enregistrée dans un trie neuf pour **chaque diagramme**.
  Piste : un jeu de fonctions standards pré-construit et partagé en lecture
  seule. C'est exactement le type de trouvaille visé.
- **Le reste du profil est plat** : aucune méthode au-dessus de 1,2 % en
  attribution. C'est normal pour 395 diagrammes différents sur une JVM
  froide (chargement de classes, interpréteur, C1). **Une suite de
  non-régression n'est pas un benchmark** : pour trouver un hotspot précis,
  il faut une charge ciblée et répétée (voir `bench_test` plus bas).

## Pièges rencontrés

- **Échantillonnage trop lâche par défaut** : `settings=profile`
  échantillonne toutes les 10 à 20 ms. Sur les 8 s de Vega, on n'obtient que
  **242 échantillons**, et le classement n'est que du bruit. La surcharge
  par événement, disponible depuis le JDK 17,
  `jdk.ExecutionSample#period=1ms` dans `-XX:StartFlightRecording`, donne
  **2333 échantillons** et fonctionne.
- **`jfr view hot-methods` (JDK 21) ne suffit pas** : c'est une vue « self »
  plate, dominée par le JDK (`AbstractStringBuilder.ensureCapacityInternal`,
  Xerces, `HashMap.getNode`…), sans lien avec le code du projet ni
  positions. D'où la recommandation de faire l'agrégation nous-mêmes avec
  `RecordingFile`.
- **Vue « self » : les frames ne sont pas des singletons.**
  `RecordedStackTrace.getFrames()` renvoie de nouveaux objets à chaque
  appel, donc comparer des frames par `==` échoue en silence (la vue self
  sortait vide). Il faut comparer des index.
- **Classes cachées** : les lambdas apparaissent comme
  `VegaTest$$Lambda/0x….execute` et se rattacheraient à `VegaTest.java` par
  le préfixe. Il faut les exclure explicitement.
- **`JAVA_TOOL_OPTIONS` n'est qu'un raccourci de prototype**, pas la
  solution cible. Il **remplace** la variable héritée : dans la sandbox, cela
  a fait disparaître les réglages de proxy/truststore, ce qui est sans effet
  ici mais ne le serait pas pour un test qui fait du réseau. Il s'applique
  aussi à toute JVM que le test forkerait lui-même. Les options doivent aller
  dans `ProjectTests.command()`.
- **Coût de JFR** (VegaTest, 395 tests, bruit de mesure important) : sans
  JFR 7081 ms et 6788 ms ; `profile` par défaut 7654 ms ; échantillonnage à
  1 ms 8262 ms. Cela fait environ **+10 à +20 %**, acceptable pour du
  profilage mais à ne pas activer par défaut.

## Proposition d'architecture (ordre conseillé)

### Phase 0 — socle, petit et immédiatement utile

1. **`set_test_jvm_options <options>`** (+ remise à zéro par
   `reset_test_settings`), par connexion comme `set_test_env`. Les options
   sont insérées dans `ProjectTests.command()` avant `-cp`. Cela couvre
   `-Xmx`, le choix du GC, `-Xlog:gc`, `-XX:+PrintCompilation`, `-Xint`,
   `-XX:TieredStopAtLevel=1`, etc.
2. **Mesures par test dans `TestRunnerMain`** :
   - temps mural ;
   - temps CPU (`ThreadMXBean.getCurrentThreadCpuTime`) ;
   - octets alloués (`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`) ;
   - nombre de GC et temps de GC (`GarbageCollectorMXBean`).

   Ce sont des colonnes en plus sur `PASS`/`FAIL`. `run_tests` peut alors
   trier par lenteur ou par allocation. Limite : un test qui lance ses
   propres threads échappe au CPU et à l'allocation par thread.

### Phase 1 — le cœur : `profile_test` / `profile_report`

3. **`profile_test <position>`** (et `profile_tests <all>`) : même chemin que
   `run_test`, avec en plus
   `-XX:StartFlightRecording=filename=<.clide/tmp/profiles/…>.jfr,settings=profile,jdk.ExecutionSample#period=1ms,dumponexit=true`
   et `-XX:FlightRecorderOptions=stackdepth=256`. Le dossier
   `.clide/tmp` est déjà gitignoré côté clide. Le résultat donne le verdict
   des tests (comme `run_test`) puis la vue `overview` et les N premières
   lignes des vues CPU attribué et allocation.
4. **`profile_report <vue> [filtre]`** : ré-interroge **le dernier `.jfr`
   sans relancer**. Vues : `hot`, `inclusive`, `self`, `lines`, `jdk`,
   `alloc`, `alloc_types`, `gc`, `contention`, `exceptions`. Il faut en plus
   une vue **`callers <méthode>`** / **`callees <méthode>`** : l'arbre
   d'appels échantillonné autour d'un hotspot, pour répondre à « qui appelle
   `TrieImpl.getOrCreate` si souvent ? ». Le port de `JfrReport.java` va dans
   un package `clide.profile`, et les classes du modèle dans `clide.model`
   (`ProfileOutcome`…).
5. **Portée** : attribuer par défaut aux racines **main** du projet (connues
   de jdtls, comme `ProjectTests.outputFolders()` pour les sorties), avec
   une option pour inclure le code de test. Le constat Vega montre que le
   défaut doit être `main`.

### Phase 2 — mesurer une charge, comparer des versions

6. **`bench_test <position> <warmup> <iterations>`** : ré-exécute la même
   méthode de test N fois **dans la même JVM** (boucle dans
   `TestRunnerMain`, via le launcher JUnit) et rapporte le minimum, la
   médiane, le p90 et l'allocation par itération. Combiné avec
   `profile_test`, cela donne assez d'échantillons sur un code chaud (JIT
   C2) pour qu'un hotspot ressorte. C'est la réponse au « profil plat »
   de Vega.
7. **`compare`** : la même mesure sur une référence (un jar via
   `set_test_classpath_prefix`, qui existe déjà) puis sur le build
   courant, avec l'écart et un indicateur de bruit. En Lua, cela permet des
   garde-fous du type « aucun test plus de 10 % plus lent ».

### Phase 3 — optionnel

8. **Agent ASM de comptage ciblé** (`-javaagent`, méthodes désignées par
   position) : comptages d'appels exacts, ce que l'échantillonnage ne donne
   pas. ASM est disponible via JaCoCo, voir `JACOCO.md`. Le surcoût est
   élevé : on l'utiliserait pour compter, pas pour chronométrer.
9. **Histogramme de classes** en fin de run (MBean `DiagnosticCommand`,
   `gcClassHistogram`) et profilage de fuites JFR (`OldObjectSample`).
10. **async-profiler** en option sous Linux/macOS seulement : il n'existe
    pas pour Windows, qui est le poste principal d'Arnaud. JFR reste le
    défaut multiplateforme.

## État d'avancement

**Phase 0 : faite.** `set_test_jvm_options` (réglage par connexion, vidé par
`reset_test_settings`, inséré par `ProjectTests.command()` entre `-ea` et
`-cp`) et les mesures par test (temps mural, CPU du thread, octets alloués,
GC) : cinq champs ajoutés à la fin des enregistrements `PASS`/`FAIL` de
`TestRunnerMain` (`TestMeter`), portés par `TestOutcome.measure()` (`TestMeasure`),
affichés après chaque test, exposés en Lua, et utilisés par
`run_tests slowest|heaviest` pour classer.
**Phase 1 : faite.** `profile_test <position>`, `profile_tests` (sans
paramètre) et `profile_report <vue> [filtre]` ; le port de `JfrReport` vit dans
`clide.profile` (`JfrAnalyzer`, `ProfileViews`, `Recording`, `ProfileScope`) et
les modèles dans `clide.model` (`ProfileOverview`, `ProfileRow`, `ProfileTable`).
Le JFR va dans `.clide/tmp/profiles/` ; seul le dernier est gardé, par démon.
Vues : `overview`, `hot`, `inclusive`, `self`, `lines`, `jdk`, `alloc`,
`alloc_types`, `contention`, plus `callers`/`callees <méthode>`. Les vues `gc`
et `exceptions` sont dans `overview` (compteurs). Point 5 (portée) : `main` par
défaut, `set_profile_scope all` pour inclure les tests ; classes cachées
(lambdas) exclues. Les phases 2 et 3 restent à faire ;
les points ci-dessous qui ne concernent que la phase 0 sont réglés (point 2 en
partie : seule la partie « options JVM » de `command()` est faite).

## Ce qui reste à concevoir/implémenter (pas encore fait)

1. **Noms des commandes** : `profile_test`/`profile_report`/`bench_test`
   séparées, ou des options sur `run_test` ? Le style du repo (un mot-clé,
   un token par ligne) plaide pour des commandes séparées.
2. **Modifier `ProjectTests.command()`/`fork()`** pour accepter une liste
   d'options JVM (phase 0), puis les options JFR (phase 1). Le test unitaire
   existant sur `command()` (qui vérifie `-ea`) est le modèle à suivre.
3. **Timeout** : `profile_tests all` ralentit la suite de 10 à 20 %, donc
   vérifier `SUITE_TIMEOUT_SECONDS`.
4. **Taille et nettoyage des `.jfr`** : ~6 Mo pour 8 s à 1 ms. Garder
   uniquement le dernier `.jfr` par session, ou les N derniers ?
5. **Forme du résultat** dans l'esprit de `RESULTS.md`/`TestOutcome` :
   positions relatives au projet (voir la règle « jamais de chemin absolu »
   dans `TODO.md`) et des tables Lua pour les scripts.
6. **Vérifier `run_tests all`** (mode `--scan`, plusieurs racines, donc
   plusieurs JVM forkées : un `.jfr` par racine à fusionner, ou un nom de
   fichier par racine).
7. **Racines « main » vs « test »** : les obtenir de jdtls (les attributs
   `test` du `.classpath` Eclipse) plutôt que de deviner à partir de
   `src/main/java`.
8. **Documentation** : `CLAUDE.md` (tableau des commandes de test),
   `RESULTS.md`, `TESTS.md`, `TODO.md`, selon les conventions du repo.

## Détails d'environnement utiles pour reprendre

- Sandbox : OpenJDK 21.0.10 (JDK complet, `javac` et `jfr` présents), `ant`
  installé par `apt-get install ant`. **Maven Central et `services.gradle.org`
  sont bloqués (403)**. PlantUML se construit malgré tout avec `ant dist`,
  grâce aux stubs de `.clide/`.
- Tests PlantUML : copier `clide/lib/{junit,opentest4j,apiguardian,xmlunit}*.jar`
  dans `plantuml/.clide/tmp/jar-junit/`.
- Client non interactif : `printf 'cmd\narg\n…\nexit\n' | python3 clide.py <projet>`
  (un token par ligne).
- Prototype : `scripts/JfrReport.java`, avec en usage
  `java scripts/JfrReport.java <x.jfr> <racine-projet> <src-root>... [--scope <src-root>]... [--top N]`.
