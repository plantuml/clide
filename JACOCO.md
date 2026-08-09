# Ajout d'une commande de couverture de tests (JaCoCo) à clide

Ce document résume l'exploration et le prototypage faits pour ajouter une
commande de couverture de tests JUnit à **clide**
(`C:\github\clide`, https://github.com/plantuml/clide), en complément de la
commande `run_test` existante. Point de départ pour reprendre le travail dans
une nouvelle conversation.

## Contexte : comment `run_test` fonctionne aujourd'hui

- `run_test <position>` (voir `src/main/java/clide/command/testrun/RunTestCommand.java`)
  résout une position en classe/méthode de test, puis délègue à
  `ProjectTests.runSelection()`.
- `ProjectTests.fork()` (`src/main/java/clide/test/ProjectTests.java`) forke une
  JVM séparée sur le classpath de test du projet (lu via jdtls,
  `session.testClasspath()`) + le classpath propre de clide en dernier
  (`ownClasspath()`), et lance `clide.test.TestRunnerMain` dedans.
- `TestRunnerMain` (`src/main/java/clide/test/TestRunnerMain.java`) exécute les
  tests via JUnit Platform Launcher et imprime un protocole texte dull
  ligne-par-ligne sur stdout (`PASS`/`FAIL`/`SKIP`/`SUMMARY`), lu et interprété
  par `ProjectTests` côté process clide (jamais par la JVM forkée elle-même).
- Aucun outil de build n'est impliqué : le classpath vient de jdtls, qui a
  déjà compilé le projet.
- `run_test`/`run_tests` ne recompilent jamais — ils rapportent l'état du
  dernier build (`rebuild` doit être lancé après une édition).
- Contrainte d'environnement forte : **clide doit se builder uniquement avec
  `ant`** (jamais `gradlew`/Gradle — `services.gradle.org` retourne 403 depuis
  la sandbox Claude) et tourner uniquement via `java -jar clide.jar
  <project>` (jamais classes + `lib/` sur le classpath — `clide.jar` embarque
  des ressources lues au runtime, comme le zip jdtls et les jars JUnit
  vendor).

## Objectif

Ajouter une commande de couverture de code (nom pas encore figé —
`run_test_coverage <position>` en écho à `run_test`, ou `coverage <all|position>`
en écho à `run_tests <all|failures>`) qui rapporte la couverture JUnit d'un
run de test, par classe/méthode/ligne.

## Approche retenue : JaCoCo, agent + CLI report

- **Un seul jar à ajouter dans `lib/`** : `jacocoagent.jar` (extrait du zip de
  release JaCoCo). Instrumente au runtime, aucun changement de bytecode à la
  compilation.
- Optionnellement `jacococli.jar` si on choisit d'invoquer la génération de
  rapport en process séparé plutôt qu'en appel direct à
  `org.jacoco.cli.internal.Main` — voir "Point ouvert : lecture du .exec"
  ci-dessous.
- **Téléchargement testé et validé** : `jacoco-0.8.13.zip` se télécharge sans
  problème depuis
  `https://github.com/jacoco/jacoco/releases/download/v0.8.13/jacoco-0.8.13.zip`
  (domaine `release-assets.githubusercontent.com`/`github.com`, déjà
  whitelisté pour la sandbox Claude — contrairement à
  `services.gradle.org`). Le zip contient :
  - `lib/jacocoagent.jar` — l'agent `-javaagent`
  - `lib/jacococli.jar` — CLI (rapport, merge, dump...), **avec ASM
    ré-empaqueté en interne** sous `org.jacoco.cli.internal.asm.*`
  - `lib/jacocoant.jar` — tâches Ant (pas nécessaire ici)
  - `lib/org.jacoco.core-*.jar`, `lib/org.jacoco.agent-*.jar`,
    `lib/org.jacoco.report-*.jar` — jars "nus", **sans ASM** (voir piège
    ci-dessous)

## Prototype réalisé (bout en bout, validé dans la sandbox)

1. **Setup sandbox** : `apt-get install ant` puis
   `apt-get install openjdk-21-jdk-headless` (le JDK par défaut de la sandbox
   n'a que le JRE, pas `javac` — `release version 21 not supported` sinon).
2. **Build de clide** : `cd C:\github\clide && ant` → `BUILD SUCCESSFUL`,
   produit `clide.jar`.
3. **Projet Java jouet** créé pour le test (`demo.Calc`) avec une méthode
   `add` appelée par un test et deux méthodes (`div`, `neverCalled`) jamais
   appelées, pour avoir un cas de couverture partielle à observer.
4. **Run de test avec l'agent JaCoCo attaché**, en reproduisant exactement
   la commande que `ProjectTests.fork()` construit aujourd'hui, avec un
   `-javaagent` en plus :
   ```
   java -javaagent:<jacoco>/lib/jacocoagent.jar=destfile=jacoco.exec \
     -cp "out/main:out/test:clide.jar" \
     clide.test.TestRunnerMain --class demo.CalcTest
   ```
   → sortie de `TestRunnerMain` strictement identique à sans l'agent
   (`PASS demo.CalcTest addWorks addWorks()` / `SUMMARY 1 1 0 0 958`), plus un
   fichier `jacoco.exec` généré en sortie. **Confirme que l'agent n'interfère
   pas avec le protocole que `ProjectTests` lit déjà.**
5. **Lecture du `.exec` et génération d'un rapport**, testée en process
   séparé via la CLI JaCoCo :
   ```
   java -jar jacococli.jar report jacoco.exec \
     --classfiles out/main \
     --sourcefiles src/main/java \
     --csv coverage.csv \
     --xml coverage.xml
   ```
   → rapporte correctement `Calc` : 2 méthodes/2 lignes couvertes (`add` +
   constructeur implicite), 2 méthodes/2 lignes manquées (`div`,
   `neverCalled`). Le XML donne le détail ligne par ligne
   (`<line nr="9" mi="4" ci="0".../>` pour la ligne du corps de `div`, jamais
   exécutée), donc pas seulement des compteurs agrégés — on peut remonter les
   numéros de ligne précis non couverts, dans le même esprit que les
   `path:line` que `run_test` rapporte déjà pour les échecs.

## Piège rencontré et évité

- Tenter de lire le `.exec` directement via l'API nue
  `org.jacoco.core.analysis.*` (`Analyzer`, `CoverageBuilder`) échoue avec
  `NoClassDefFoundError: org/objectweb/asm/ClassVisitor` : les jars
  `org.jacoco.core-*.jar`/`org.jacoco.report-*.jar` du zip de release
  déclarent ASM comme dépendance externe, pas ré-empaquetée — il faudrait
  ajouter un jar ASM séparé (encore une dépendance, encore un
  téléchargement).
- **`jacococli.jar` seul suffit** et évite ce problème : il ré-empaquette ASM
  sous son propre namespace (`org.jacoco.cli.internal.asm.*`), donc une seule
  archive à vendorer, cohérent avec la contrainte "zéro dépendance réseau au
  runtime, tout vient du jar".

## Ce qui reste à concevoir/implémenter (pas encore fait)

1. **Nom et signature de la commande** — décision à prendre : `run_test_coverage
   <position>` (même shape que `run_test`) vs. une commande à part `coverage`.
2. **Modifier `ProjectTests.fork()`** pour ajouter conditionnellement
   `-javaagent:<jacocoagent.jar>=destfile=<tmp>.exec` à la commande construite
   — uniquement pour la nouvelle commande, `run_test`/`run_tests` restent
   inchangés. Le fichier `.exec` peut aller dans
   `.clide/tmp/jar-junit/` ou un nouveau sous-dossier `.clide/tmp/coverage/`
   (cohérent avec où clide range déjà ses jars JUnit extraits, voir
   "Getting started" dans `CLAUDE.md`).
3. **Génération et lecture du rapport côté clide** : deux options à trancher
   - (a) invoquer `jacococli.jar` en process séparé (comme la CLI testée
     ci-dessus), parser le XML produit ;
   - (b) appeler directement `org.jacoco.cli.internal.Main.main(...)` en
     intra-process (le jar est déjà sur le classpath de clide), pour éviter
     le coût d'une 3e JVM forkée, puis parser le même XML — probablement
     préférable pour la latence, à valider.
   - Dans les deux cas, il faut un parseur XML simple (le format JaCoCo XML
     est plat et documenté, DTD `report.dtd` visible dans la sortie
     ci-dessus) pour en extraire, par classe : compteurs
     INSTRUCTION/LINE/METHOD/BRANCH missed/covered, et par ligne, le
     statut couvert/manqué.
4. **Mapper vers `--classfiles`** : `jacococli report` a besoin du dossier des
   `.class` compilés — `ProjectTests.outputFolders()` calcule déjà cette
   liste pour `run_tests`, réutilisable telle quelle.
5. **Forme du résultat renvoyé par la commande** : à concevoir dans le même
   esprit que `TestOutcome`/`RESULTS.md` (voir `CLAUDE.md`, section "Reading a
   result") — probablement un nouveau type `CoverageOutcome` avec, par classe,
   les compteurs et la liste des lignes manquées en position clide
   (`path:line`), pour rester chaînable avec `find_symbol`/`hover` comme le
   reste des commandes.
6. **`build.xml`** : ajouter `jacocoagent.jar` (et `jacococli.jar` si l'option
   (a) ou (b) l'exige) aux jars embarqués dans le fat jar, sur le modèle de ce
   qui existe déjà pour les jars JUnit vendor (voir les patternsets
   `fatjar.redundant.jars` et les cibles autour de `dist`/`explode-libs`).
7. **Cas `run_tests`/scan complet** : le prototype n'a testé que le cas
   `--class` (une seule classe). Le cas `--scan <output-folder>` (utilisé par
   `runEverything`) devrait fonctionner à l'identique avec l'agent, mais pas
   encore vérifié — à tester, notamment sur PlantUML réel (bien plus gros que
   le projet jouet à 1 classe utilisé ici) pour voir le temps que prend
   `jacococli report` sur des centaines de classes.
8. **Documentation** : mettre à jour `CLAUDE.md` (tableau "Tests of the opened
   project"), `RESULTS.md` (nouvelle forme de résultat), `TODO.md`/`TESTS.md`
   selon les conventions déjà en place dans le repo pour documenter une
   nouvelle commande.

## Détails d'environnement utiles pour reprendre

- Sandbox testée avec JDK 21 (`openjdk-21-jdk-headless`, à installer
  explicitement — le JDK par défaut n'a pas `javac`).
- `ant`/`ant-optional` à installer via `apt-get install ant` (paquets
  `archive.ubuntu.com`, déjà whitelistés).
- URL de téléchargement JaCoCo validée :
  `https://github.com/jacoco/jacoco/releases/download/v0.8.13/jacoco-0.8.13.zip`
  (4.1 Mo, contient `lib/*.jar`).
- Projet jouet utilisé pour le prototype (à recréer si besoin, pas commité
  nulle part) : `demo.Calc` avec `add`/`div`/`neverCalled`, un seul test
  `CalcTest.addWorks`.
