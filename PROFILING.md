# Ajout de mesures de performance (profilage JFR) à clide

Ce document résume l'exploration et le prototypage faits pour que **clide**
(https://github.com/plantuml/clide) mesure les performances
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
(lambdas) exclues.

**Phase 2 : faite.** `bench_test <position> <warmup> <iterations>` : la boucle
est dans `TestRunnerMain` (`--bench <warmup> <iterations>` devant le sélecteur),
qui rejoue la même requête JUnit dans la même JVM et écrit un enregistrement
`ITER <n> <W|M> <mesure>` par itération (somme des mesures des tests de
l'itération) au lieu des `PASS` ; un échec arrête la boucle. `clide.bench.BenchStats`
en tire min / médiane / p90 / max (`BenchStat`, `BenchReport`) et l'écart
(p90 − min) / médiane. `profile_bench` ajoute le JFR (échauffement compris, un
JFR ne sait pas les distinguer) et `compare_test <position> <warmup>
<iterations> <référence>` lance deux JVM, la référence d'abord (entrées mises
devant le classpath, comme `set_test_classpath_prefix`), puis le build courant :
delta des médianes, bruit et verdict `slower`/`faster`/`same` (voir la suite
ci-dessous pour leur forme actuelle). Délai : 600 s par JVM (le délai d'une suite),
bornes 1000 / 1000. `run_tests all` sur plusieurs racines reste ouvert (voir
la fin) : un benchmark vise un test, donc une seule racine. La phase 3 reste à faire.

**Phase 2, suite (premier retour d'usage, voir plus bas).** Quatre corrections
issues de l'emploi réel sur PlantUML :

- **`-da` pour toute mesure.** `profile_test`, `profile_tests`,
  `profile_bench`, `bench_test` et `compare_test` ajoutent `-da` juste après le
  `-ea` de la ligne de commande (`ProjectTests.jvmOptions`), avant les options
  de la connexion : `set_test_jvm_options -ea` les rétablit, la dernière option
  gagnant. `run_test`/`run_tests` gardent `-ea`.
- **Verdict par mesure.** `Comparison` porte trois `Metric(deltaPercent,
  noisePercent, verdict)` : `wall`, `cpu`, `allocated`. Le verdict de
  `compare_test` n'est plus celui du seul temps mural.
- **Bruit robuste.** Le bruit n'est plus (p90 − min) / médiane, que n'importe
  quelle itération gâchée fait exploser et qui ne diminue jamais avec le nombre
  d'itérations. `BenchStat` porte maintenant les quartiles (`q1`, `q3`), et le
  seuil est le double de l'erreur standard de la différence des deux médianes
  (`0,929 × IQR / √n` par run), plancher 2 % pour un temps et 1 % pour une
  allocation. Au-delà de 10 % de bruit sur le temps mural, `compare_test` dit
  que plus d'itérations le resserreraient. `bench_test` affiche en plus
  `error`, ce que vaut la médiane de chaque mesure.
- `bench_test`/`compare_test`/`profile_bench` rendent maintenant un échec de
  test comme `run_test` (c'était une erreur interne de rendu).

## Premier retour d'usage : optimiser PlantUML avec clide

Une session complète a suivi la chaîne `profile_test` → patch → `compare_test`,
sur des diagrammes SVG répétés (2400, puis 400 par itération).

- **Ce qui a marché tel quel.** Le profil de la suite Vega était plat, comme
  prévu ; une charge ciblée et répétée a fait ressortir des coûts par
  diagramme : un tampon de 16 Ko par lecteur de source
  (`ReadLineReader.<init>`), un cache de clés de skinparam par instance, des
  `TextLayout` reconstruits pour chaque descente de police, et surtout la
  construction du registre de fonctions du préprocesseur — celle que le
  prototype avait repérée (`TContext.<init>`, ~3 % du CPU, ~11 % de
  l'allocation). Un registre partagé en lecture seule l'a supprimée.
- **Résultat mesuré** (`compare_test`, 15 itérations de chauffe, 30 mesurées,
  contre le commit précédent) : environ −20 % d'allocation, −10 % de CPU,
  −10 % de temps mural. L'allocation est stable à ±1 % d'une mesure à l'autre ;
  le temps mural, lui, varie de −9 à −17 % selon les passes.
- **Ce que le premier `compare_test` ne disait pas bien.** Il répondait `same`
  partout : son bruit (p90 − min) valait 35 à 45 %, avec un allocation à ±1 %
  qu'il ignorait. D'où le verdict par mesure et le bruit fondé sur les
  quartiles, ci-dessus.
- **Pièges rencontrés.**
  - Les assertions (`-ea`) ont fabriqué un faux point chaud
    (`Pattern2.compileInternal`, un `assert` qui recompile une regex pour la
    vérifier) : d'où `-da`.
  - Avec `set_max_results 0`, toutes les lignes des vues de profil disparaissent.
  - Avec 3 itérations de chauffe, le bruit mesuré atteignait 116 % : une
    chauffe courte rend n'importe quel comparatif inutilisable.
  - Il faut écrire un test JUnit (jetable) qui est la charge ; une référence
    est un jar ou un dossier de classes qu'il faut construire soi-même.

## Ce qui reste à faire

Fait depuis la première rédaction de ce document : les noms de commandes
(commandes séparées), `ProjectTests.command()`/`fork()` pour les options JVM
et JFR, le délai d'une suite pour `bench_test`, un seul `.jfr` gardé par
démon, la portée `main` par défaut, les tables Lua, la documentation.

Ouvert :

1. **Charge sans fichier de test** : `compare_test` et `bench_test` demandent un
   test JUnit ; une charge « méthode statique » ou un petit script Lua éviterait
   d'ajouter un fichier au projet pour mesurer.
2. **Référence par commit** : accepter un commit git (checkout dans un dossier
   à part, build, jar) au lieu d'un jar ou d'un dossier de classes construit à
   la main.
3. **Alternance A/B/A/B** : les deux JVM tournent l'une après l'autre, donc la
   dérive de la machine (thermique, voisins) se lit comme un écart. Alterner
   plusieurs paires de JVM courtes la répartirait.
4. **Avertissement de chauffe** : dire quand la dispersion reste haute après la
   chauffe (la médiane des premières itérations contre celle des dernières).
5. **`run_tests all`** (mode `--scan`, plusieurs racines, donc plusieurs JVM et
   plusieurs `.jfr`) : à vérifier sous `profile_tests`.
6. **Racines « main » vs « test »** : les obtenir de jdtls (attributs `test` du
   `.classpath`) plutôt que de deviner à partir de `src/main/java`.
7. **Phase 3** : l'histogramme de classes en fin de run (comparer des
   allocations avant/après) est le plus utile ; l'agent de comptage d'appels
   l'est moins, `profile_report callers` répondant déjà à « qui appelle ça ? ».
8. **`JACOCO.md`** cite encore le chemin local d'un poste (`C:\github\clide`)
   comme ce document le faisait : à nettoyer de la même façon.

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
