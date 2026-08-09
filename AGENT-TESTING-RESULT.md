# AGENT-TESTING-RESULT.md — campagne C6

Compte rendu de la campagne de test décrite dans `AGENT-TESTING.md`.
Continuation directe de `TESTS.md` (campagnes C1 à C5) : la numérotation des
points reprend là où C5 s'est arrêtée (dernier point existant : 21).

---

## 1. En-tête

| | |
|---|---|
| Date | 2026-08-08 |
| clide | `d59d2e9` (clone `--depth 1` de `https://github.com/plantuml/clide`) |
| PlantUML | `f3f8076` (clone `--depth 1`, branche par défaut) |
| JDK | OpenJDK 21.0.10+7-Ubuntu-124.04 |
| Ant | Apache Ant 1.10.14 (absent de l'image, installé via `apt-get install -y ant`) |
| Machine | Linux sandbox, **2 cœurs**, 8 Go de RAM, ~30 Go libres |
| Fichiers Java | clide : 137 · PlantUML : 3633 |

Particularités d'environnement, toutes utilisées comme conditions de test :

- **`JAVA_TOOL_OPTIONS` est positionnée** (truststore + proxy). Chaque JVM fille
  écrit `Picked up JAVA_TOOL_OPTIONS: …` sur stderr. C'est le déclencheur du
  point 17 — et il s'est révélé pire que décrit (voir §4.2).
- **Pas de X11** : `java.awt.headless` de fait. C'est la cause de 70 des
  79 « échecs » de la suite PlantUML.
- **Pas d'accès réseau sortant utile** depuis les tests (`https://plantuml.com`
  injoignable) — 1 échec + 1 assumption.
- **ELK absent du classpath** de test → 3 échecs `ClassNotFoundException:
  org.eclipse.elk.graph.util.ElkGraphUtil`.

Aucun commit, aucun push, sur aucun des deux dépôts. Les deux `git status` sont
propres en fin de campagne (§8.4).

---

## 2. Mise en place

### 2.1 Build Ant — mesures

| Cible | Mesure C6 | Annoncé dans `AGENT-TESTING.md` §4.1 | Écart |
|---|---|---|---|
| `ant dist` | **6,99 s** | ≈ 9 s | plus rapide |
| `clide.jar` | **56 230 399 o** (54 Mio / 56 Mo) | 56 Mo | conforme |
| `ant test` | **11,83 s** | ≈ 11 s | conforme |
| tests exécutés | **500 tests, 0 échec** | 435 tests, 0 échec | **+65 tests** |

L'écart sur le nombre de tests n'est pas un symptôme : le dépôt a simplement
grossi depuis la rédaction d'`AGENT-TESTING.md`. Le chiffre à retenir est
« 500 / 0 échec », et c'est lui qui sert de référence au §4.3.

Aucun accès réseau n'a été nécessaire pour construire clide, conformément à ce
qui est annoncé.

### 2.2 Ressources embarquées dans le jar

```
$ unzip -l clide.jar | grep -E "resource/jdt|resource/vendor-junit"
 49061902  resource/jdt-language-server-latest.zip
        0  resource/vendor-junit/
   197537  resource/vendor-junit/junit-pioneer-2.3.0.jar
  2680711  resource/vendor-junit/junit-platform-console-standalone-1.10.1.jar
   179979  resource/vendor-junit/xmlunit-core-2.12.0.jar
```

Les deux familles de ressources sont présentes. **Toute la campagne a été menée
avec `java -jar clide.jar`**, jamais avec `-cp build/classes` — le piège du §4.3
d'`AGENT-TESTING.md` n'a donc pas été rencontré, et l'absence d'erreurs
`org.junit.platform.launcher cannot be resolved` sur les deux projets le
confirme a contrario.

### 2.3 Démarrage à froid, reconnexion

| Mesure | C6 | Annoncé |
|---|---|---|
| Démarrage à froid sur `/tmp/demo` (1 fichier, extraction jdtls comprise) | **22,5 s** | ~22 s |
| Démarrage à froid sur clide (137 fichiers) | **23,0 s** | — |
| Démarrage à froid sur PlantUML (3633 fichiers) | **39,6 s** | — |
| Reconnexion à un daemon vivant | **0,090 – 0,103 s** | ~0,25 s |

La reconnexion est donc **2,5× plus rapide** que ce qu'annonce `CLAUDE.md`.

---

## 3. Ce qui marche

### 3.1 Compiler et obtenir les erreurs exactes — la priorité n°1 tient

Erreur volontaire introduite dans PlantUML avec un outil d'édition externe
(méthode sonde appelant une méthode inexistante, `UrlBuilder.java` ligne 49) :

```
$ printf 'rebuild\nerrors\nexit\n' | java -jar clide.jar /tmp/plantuml
rebuild: 1 file(s) changed since the last build, rebuilt in 9303 ms
src/main/java/net/sourceforge/plantuml/url/UrlBuilder.java:
  [error] line 49: The method thisMethodDoesNotExist(int) is undefined for the type UrlBuilder
jdtls: 1 error(s), 1300 warning(s) in 585 file(s)
```

Fichier, ligne et message exacts. Le compte de fichiers en warning passe bien de
584 à 585 puis revient à 584 une fois corrigé.

**Rafraîchissement du modèle sémantique** vérifié séparément : après avoir
corrigé le corps de la sonde et fait un `rebuild`, `find_symbol probeC6` la
trouve immédiatement, position complète et md5 à jour :

```
find_symbol: 1 symbol(s)
[method] ecfbc9ad…:src/main/java/net/sourceforge/plantuml/url/UrlBuilder.java:48:13:probeC6 public int probeC6() {
```

### 3.2 Le scénario « refactor incomplet » — le meilleur résultat de la campagne

Signature changée sur l'interface la plus implémentée de PlantUML :
`UDrawable.drawU(UGraphic ug)` → `drawU(UGraphic ug, boolean debugC6)`.

```
rebuild: 2 file(s) changed since the last build, rebuilt in 10667 ms
jdtls: 1034 error(s), 1309 warning(s) in 937 file(s)
jdtls: 100 diagnostic(s) shown out of 1034, truncated - raise the limit with set_max_results
```

**1034 erreurs sur 456 fichiers, en 10,7 s**, avec la décomposition suivante
(obtenue par `set_max_results 5000` + `print_diagnostics errors`, qui ne
recompile pas) :

| Nature | Nombre |
|---|---|
| `must implement the inherited abstract method` (implémentation manquante) | 460 |
| `is not applicable for the arguments` (site d'appel) | 485 |
| `must override or implement a supertype method` (`@Override` devenu faux) | 88 |

Les **classes anonymes sont couvertes** :

```
src/main/java/net/sourceforge/plantuml/wire/WireDiagram.java:
  [error] line 74: The type new TextBlock(){} must implement the inherited abstract method UDrawable.drawU(UGraphic, boolean)
```

La contre-vérification de ce résultat contre `grep` est au §5.1 — c'est là que
se mesure la valeur réelle de l'outil.

### 3.3 Requêtes sémantiques

Toutes exercées sur les deux projets, toutes correctes :

- `find_symbol` — fuzzy comme annoncé (`find_symbol DiagramType` remonte aussi
  les méthodes `diagramType()`), positions directement recollables.
- `find_declaration method` **traverse les fichiers et remonte la hiérarchie** :
  sur `tb.drawU(ug)` dans `EmbeddedDiagram.java:187:8`, où `tb` est un
  `TextBlock`, la réponse est la déclaration réelle, dans l'interface
  grand-parente :
  ```
  find_declaration: 1 location(s)
  8b203ef0…:src/main/java/net/sourceforge/plantuml/klimt/shape/UDrawable.java:42:14:drawU public void drawU(UGraphic ug);
  ```
- `find_implementation type` sur `clide.core.Command` : 24 résultats, incluant
  `ExitCommand extends DisconnectCommand` — donc **transitif**, pas seulement
  les sous-classes directes.
- `find_implementation method` sur `Command.executeCommand` : 22 résultats.
  L'écart 24 → 22 est exact : deux sous-classes n'overrident pas la méthode.
- `list_members` gère les **enum** et donne la colonne juste même quand
  16 constantes sont sur une seule ligne (`DiagramType.java:45`) — c'est
  exactement ce que la notation `<position>` avec colonne permet, et une
  fonctionnalité qu'aucune autre commande ne remplace.
- `hover` répond instantanément sur un type JDK (`String` → `java.lang.String` +
  `Source: Java 21.0.10 (module: java.base)`), ce qui confirme le contournement
  documenté du point 5.
- `search_regex` : chemins relatifs en entrée **et en sortie** (points 2 et 3,
  confirmés corrigés).

### 3.4 La notation `<position>` et le md5

Le garde-fou md5 fonctionne exactement comme décrit, et la dégradation en
l'absence de md5 aussi. Même fichier, même position, juste avant/après
restauration du fichier :

```
?ERROR FILE_MODIFIED: Stale position: …/UrlBuilder.java has changed since this position
was produced - its content no longer signs as ecfbc9ad94c446c2d554b60b022f2ec3
?ERROR NAME_NOT_ON_LINE: 'probeC6' not found on line 48 of …/UrlBuilder.java
```

La première ligne est la position **avec** md5, la seconde la **même** position
sans md5 : on obtient bien « refus net » d'un côté, « symptôme » de l'autre.

### 3.5 Échecs propres, nommés et rapides

Toute la batterie du §6.3 d'`AGENT-TESTING.md` a été passée. **Aucun échec lent,
aucun échec muet** (à une exception près, §4.2). Extrait :

```
?ERROR LINE_OUT_OF_RANGE: Line 9999 out of range (file has 195 line(s)): …/Command.java
?ERROR NAME_NOT_ON_LINE: 'Nonexistent' not found on line 42 of …/Command.java
?ERROR NAME_NOT_AT_COLUMN: 'Command' does not start at column 1 of line 42 of …/Command.java
hint: 'Command' starts at columns 23, 53 on that line
?ERROR FILE_NOT_FOUND: Not a file: src/main/java/does/not/Exist.java
?ERROR MALFORMED_POSITION: Invalid position 'DEADBEEF…' - 'DEADBEEF…' is 32 hexadecimal
characters, so it reads as a <file-content-md5>, but one is written lowercase as clide prints it
?ERROR MALFORMED_POSITION: Invalid position 'notaposition' - expected
<file-content-md5>:<file path>:<line>:<column>:<name>, the <file-content-md5> being optional
?ERROR INVALID_REGEX: Invalid regex '[unclosed': Unclosed character class near index 8
?ERROR NOT_A_DIRECTORY: Not a directory: 'nosuchdir' (resolved against the project root
/tmp/plantuml, giving /tmp/plantuml/nosuchdir)
?ERROR VALUE_OUT_OF_RANGE: maxResults must not exceed 10000
hint: the cap stays at 0
?ERROR INVALID_INTEGER: Invalid count '-1' - expected an integer of 0 or more, not a negative one
?ERROR UNKNOWN_KEYWORD: Invalid keyword 'nonexistent_command'
```

Les `hint:` sont réellement utiles : celui de `NAME_NOT_AT_COLUMN` donne les
colonnes correctes, ce qui répare la commande en un aller-retour. C'est le
message que j'ai le plus utilisé pendant la campagne (les tabulations de
PlantUML comptant pour une colonne, se tromper de colonne est la faute
courante).

`set_max_results 0` est bien honoré littéralement, et la troncature reste
sincère :

```
set_max_results: max_results 100 -> 0
find_implementation: 0 location(s) shown out of 24, truncated - raise the limit with set_max_results
```

### 3.6 `MULTI_MODULE_PROJECT` : la limite documentée, reproduite à la demande

Le checkout de clide **n'embarque pas** ses propres `.project`/`.classpath`
(ils sont gitignorés) : le cas ne se déclenche donc pas spontanément, contrairement
à ce que laisse attendre `AGENT-TESTING.md` §6.1. Provoqué à la main
(`cp .clide/tmp/.project.clide .project`, idem `.classpath`, puis daemon neuf),
il se reproduit exactement comme `CLAUDE.md` le décrit :

```
(4/4) Building project ... [OK] (imported via a temporary .project/.classpath,
      the project's own restored afterward - see .clide/tmp/ for what was actually used)
?ERROR MULTI_MODULE_PROJECT: this repository holds 2 modules and clide cannot yet be told
which one to test: file:/tmp/clide/, file:/tmp/clide/.clide/tmp/
```

Le second module est bien `.clide/tmp/` — la cause annoncée est la bonne. À
noter : dans cet état, `run_test` **et** `run_tests` sont refusés, mais toutes
les commandes sémantiques continuent de répondre normalement. La dégradation est
donc partielle et propre.

### 3.7 Le mode `--lua`

Non demandé par `AGENT-TESTING.md`, mais documenté dans `CLAUDE.md` et donc
testé. Il marche, et il a trouvé quelque chose (§6.2) :

```lua
local members = list_members("src/main/java/net/sourceforge/plantuml/core/DiagramType.java:43:13:DiagramType")
for _, member in ipairs(members.symbols.items) do
  if member.kind == "method" and member.location ~= nil then
    print(member.name .. "\t" .. find_reference("method", member.location.position).locations.totalCount)
  end
end
```

```
$ java -jar clide.jar --lua /tmp/audit.lua /tmp/plantuml     (1,39 s)
isLegacyUML()	0
findStartTypes(CharSequence)	54
getTypes(CharSequence, int)	1
check(String, CharSequence, int)	32
getStyleName()	27
humanReadableName()	1
```

Six `find_reference` + un `list_members` en 1,4 s et un seul aller-retour.
En mode commande-par-tour, c'était sept connexions.

---

## 4. Ce qui ne marche pas

### 4.1 `run_test` sur une méthode `@ParameterizedTest` (point 16) — toujours cassé

**Symptôme.** Toute méthode de test prenant des paramètres est inatteignable.

Reproduction sur clide lui-même (le fixture existe dans le dépôt) :

```bash
printf 'run_test\nsrc/test/java/fixture/ParameterizedOnly.java:18:7:everyValueIsPositive\nexit\n' \
  | java -jar clide.jar /tmp/clide
```

→ `?ERROR TEST_RUNNER_BROKEN: the test JVM failed to run the tests: Picked up JAVA_TOOL_OPTIONS: …`

Reproduction sur PlantUML, exactement le cas de C5 :

```bash
printf 'run_test\nsrc/test/java/net/sourceforge/plantuml/url/UrlBuilderTest.java:32:14:testUrl\nexit\n' \
  | java -jar clide.jar /tmp/plantuml
```

→ même erreur. Alors que la **classe entière** passe sans problème :
`run_test: 20 test(s), 20 passed, 0 failed in 502 ms`.

**Cause, confirmée par exécution manuelle du fork** :

```
Caused by: org.junit.platform.commons.PreconditionViolationException:
  Could not find method with name [everyValueIsPositive] in class [fixture.ParameterizedOnly].
```

`TestSelector.selector()` (`src/main/java/clide/test/TestSelector.java:49`) émet
`--method <FQCN>#<nom>` sans types de paramètres ; `TestRunnerMain.buildRequest()`
(`src/main/java/clide/test/TestRunnerMain.java:138`) le passe à
`DiscoverySelectors.selectMethod(String)`, qui interprète l'absence de
parenthèses comme « liste de paramètres vide » et n'accepte donc qu'une méthode
sans argument.

**Impact.** `@ParameterizedTest` est massivement répandu : 10 classes de test au
moins côté PlantUML, 6 côté clide. Pour un agent, « lancer le test ciblé qui
couvre ma modification » est la priorité n°2 du projet et elle est indisponible
dès que le test est paramétré.

**Piste de correction.** Ne plus passer par `selectMethod` : sélectionner la
**classe** (`DiscoverySelectors.selectClass`) et filtrer après découverte sur le
nom de méthode (`PostDiscoveryFilter` sur `MethodSource.getMethodName()`). Cela
règle du même coup les surcharges (plusieurs méthodes de même nom sont toutes
lancées, ce qui est le comportement attendu) et le point 4.3 ci-dessous.

### 4.2 Le message d'échec de `run_test` (point 17) — toujours cassé, et **camouflé**

`ProjectTests.firstLine()` (`src/main/java/clide/test/ProjectTests.java:468`)
prend littéralement la première ligne de stderr, qui est la bannière de la JVM.
Le diagnostic réel — celui du §4.1 — n'apparaît jamais.

**C5 avait décrit le problème comme « message inutilisable ». Il est pire que
ça : le message est indistinguable du bruit qu'il remplace, donc activement
supprimé par les filtres qu'on écrit spontanément.** Vécu pendant cette
campagne : mon pipeline de test était

```bash
… | java -jar clide.jar /tmp/clide 2>&1 | grep -v "Picked up" | tail -20
```

Le `grep -v "Picked up"` a **effacé la ligne `?ERROR TEST_RUNNER_BROKEN` en
entier**, parce que le texte de l'erreur contient la bannière. Résultat observé :
`run_test` répond **absolument rien**, en 0,4 s. J'ai d'abord noté « échec muet,
bug grave » avant de refaire la mesure en capture brute (`> A.out 2> A.err`) et
de retrouver les 877 octets réellement émis.

Autrement dit, l'exact contre-pied de la note de méthode de C5 : là où C5 a cru à
une sortie vide en filtrant *un message utile*, C6 a cru à une sortie vide en
filtrant *un message inutile qui contient le bruit*. Les deux erreurs ont la même
racine et le même correctif.

**Correctif (inchangé depuis C5, deux lignes).** Dans `firstLine`, ignorer les
lignes de bruit JVM (`Picked up JAVA_TOOL_OPTIONS`, `Picked up _JAVA_OPTIONS`,
`OpenJDK … VM warning`) puis, de préférence, remonter la dernière ligne
`Caused by:` — ici `PreconditionViolationException: Could not find method with
name [everyValueIsPositive]`, qui donnait le point 16 en une lecture.

### 4.3 `run_test` sur une classe imbriquée `@Nested` (point 22, **nouveau**)

**Symptôme.** Même `?ERROR TEST_RUNNER_BROKEN` que le §4.1, pour une raison
différente. Un test qui vit dans une classe `@Nested` n'est atteignable ni par
son nom de méthode, ni par le nom de la classe imbriquée.

```bash
# PlantUML : JsonObjectTest contient @Nested class Merge_Test { … merge_fails_With_Null() }
printf 'run_test\nsrc/test/java/net/sourceforge/plantuml/json/JsonObjectTest.java:26:14:merge_fails_With_Null\nexit\n' \
  | java -jar clide.jar /tmp/plantuml
```

**Cause.** `TestSelector` déduit le nom de classe **du seul nom de fichier**
(`TestSelector.typeName()`, `src/main/java/clide/test/TestSelector.java:53`), et
tout symbole différent de ce nom est traité comme une méthode de ce type. Vérifié
en rejouant le fork à la main :

```
MethodSelector [className = 'net.sourceforge.plantuml.json.JsonObjectTest',
                methodName = 'merge_fails_With_Null', parameterTypes = '']  resolution failed
Caused by: PreconditionViolationException: Could not find method with name
[merge_fails_With_Null] in class [net.sourceforge.plantuml.json.JsonObjectTest].
```

Le nom réel est `…JsonObjectTest$Merge_Test`. Viser la classe imbriquée
elle-même (`…:24:8:Merge_Test`) échoue pareillement : elle est transformée en
`JsonObjectTest#Merge_Test`.

**Impact.** `JsonObjectTest` est l'une des classes que C4 citait comme preuve que
`run_test` fonctionne — c'était vrai **au niveau classe seulement**. `@Nested`
est le style d'organisation par défaut de JUnit 5 pour les tests structurés ;
sur PlantUML, c'est le cas de `JsonObjectTest` et de plusieurs autres.

**Piste de correction.** Le même correctif qu'au §4.1 le règle en grande partie :
en sélectionnant la classe du fichier et en filtrant après découverte, les
descendants imbriqués sont dans le plan de test et le filtre les voit. Sinon, il
faut demander à jdtls le nom binaire du type englobant plutôt que de le déduire
du nom de fichier.

### 4.4 Un test `ABORTED` compté `failed` (point 18) — toujours ouvert

Reproduit avec un cobaye minimal (fichier créé puis supprimé, voir §8.4) :

```java
package fixture;
public class AbortedByAssumption {
	@Test
	void abortedByAssumption() {
		assumeTrue(false, "Cobaye C6 : assumption volontairement fausse");
		assertTrue(false);
	}
}
```

```
?ERROR TEST_FAILURES: 1 test(s) failed out of 1
run_test: 1 test(s), 0 passed, 1 failed in 490 ms
  [failed] …/AbortedByAssumption.java:16: fixture.AbortedByAssumption.abortedByAssumption
     org.opentest4j.TestAbortedException: Assumption failed: Cobaye C6 : …
```

Gradle, Maven et la console JUnit rapportent ce test `skipped`, 0 échec.

**Cause.** `TestRunnerMain.Recorder.executionFinished`
(`src/main/java/clide/test/TestRunnerMain.java:214`) ne teste que `SUCCESSFUL` ;
tout le reste incrémente `failed`. Le compteur `skipped` existe déjà et est
alimenté correctement par `executionSkipped` (les `@Disabled` sont bien comptés
`skipped`, vérifié : `fixture.DisabledClass.neverRuns`).

**Impact chiffré sur PlantUML** : 5 des 79 « échecs » de la suite complète. Pour
un agent qui pose la question « est-ce que ma modification a cassé quelque
chose », ce sont 5 faux positifs permanents, sur un dépôt intact.

### 4.5 `find_declaration type` vers un type JDK (point 5) — toujours 30 s

```bash
printf 'find_declaration\ntype\nsrc/main/java/clide/test/ProjectTests.java:468:17:String\nexit\n' \
  | java -jar clide.jar /tmp/clide
```

```
?ERROR JDTLS_REQUEST_FAILED: find_declaration failed: No response for
textDocument/typeDefinition (id=37) after 30s
```

Mesuré : **30,094 s**. L'erreur est propre, nommée et exploitable — ce que
`CLAUDE.md` ne dit pas (voir §7). Reste le coût : 30 s d'attente pour une
question à laquelle `hover` répond instantanément.

### 4.6 `run_tests` : les échecs sont hors de la fenêtre de troncature (point 23, **nouveau**)

**Symptôme.** `run_tests all` tronque à 100 entrées, et les entrées sont
imprimées **dans l'ordre d'exécution, succès compris**. Sur une suite où les
échecs arrivent tard, la sortie contient donc *zéro* ligne `[failed]` alors que
le résumé annonce des échecs.

Reproduction sur clide lui-même :

```bash
printf 'run_tests\nall\nexit\n' | java -jar clide.jar /tmp/clide
```

```
?ERROR TEST_FAILURES: 1 test(s) failed out of 520
run_tests: 520 test(s), 518 passed, 1 failed, 1 skipped in 6031 ms
[passed] …                      ← 99 lignes de succès
run_tests: 100 entry(s) shown out of 520, truncated - raise the limit with set_max_results
```

Compte réel dans cette sortie : 99 `[passed]`, 1 `[skipped]`, **0 `[failed]`**.

**Impact.** Le filtre `failures` existe et donne la bonne réponse
immédiatement — mais rien dans la sortie de `all` ne dit *quel* test a échoué,
alors que c'est la seule information qu'on cherchait. Un agent qui a lancé `all`
doit relancer toute la suite avec `failures`.

**Piste de correction.** Trier les entrées de `run_tests` échecs d'abord (le
`man` de `run_test` promet déjà « failures first-class »), ou au minimum ajouter
au message de troncature un `hint:` pointant vers `run_tests failures`.

### 4.7 `run_tests` sur clide rend la suite rouge sur un dépôt intact (point 24, **nouveau**)

**Symptôme.** Sur un checkout propre de clide, `ant test` est vert
(500 tests, 0 échec) et `run_tests` est rouge :

```
?ERROR TEST_FAILURES: 1 test(s) failed out of 520
run_tests: 520 test(s), 518 passed, 1 failed, 1 skipped in 6031 ms
[failed] src/test/java/fixture/ParameterizedFailing.java:18:
         fixture.ParameterizedFailing.everyValueIsPositive [3] -3
     valeur negative : -3 ==> expected: <true> but was: <false>
```

**Cause.** Périmètre de découverte différent, et c'est vérifiable dans les deux
sources :

- `ant test` lance `ConsoleLauncher` avec `--select-package clide`
  (`build.xml:188`) — le package `fixture` est hors périmètre ;
- `run_tests` lance `TestRunnerMain --scan`
  (`src/main/java/clide/test/TestRunnerMain.java:140`), qui balaie tout le
  classpath et ramasse donc `fixture.ParameterizedFailing` (échec voulu) et
  `fixture.DisabledClass` (skip voulu).

520 − 500 = 20, soit exactement les cinq fixtures (`PlainPassing`, `Mixed`,
`ParameterizedOnly`, `ParameterizedFailing`, `DisabledClass`).

**Impact.** Faible pour PlantUML, réel pour clide : c'est le dépôt sur lequel un
agent travaillant *sur clide* posera la question, et la réponse est un faux
« tu as cassé quelque chose ». Plus généralement, tout projet gardant des
cobayes de test volontairement rouges hors du périmètre de son build a le même
problème.

**Piste de correction.** Aucune n'est évidente et le choix est de conception :
soit `--scan` reste la règle et c'est à documenter noir sur blanc (« run_tests
ne connaît pas le périmètre de votre build »), soit clide lit le périmètre
quelque part. À trancher, pas à corriger à l'aveugle.

### 4.8 `bin/` (148 Mo) reste à la racine du projet après `terminate` (point 25, **nouveau**)

Le point 19 (jdtls dans le CWD) est corrigé (§8.1), mais la promesse de
`CLAUDE.md` — « a `git status` on the opened project never shows anything moving
at its root because of clide » — reste fausse pour une autre raison, invisible
sur clide et PlantUML parce que **tous deux gitignorent `bin`**.

Reproduction sur un dépôt neutre :

```bash
mkdir -p /tmp/demo2/src/main/java/demo && cd /tmp/demo2 && git init -q .
printf 'package demo;\npublic class Square {\n\tpublic double area() { return 4; }\n}\n' \
  > src/main/java/demo/Square.java
git add -A && git commit -qm init            # git status : propre

printf 'exit\n'      | java -jar clide.jar /tmp/demo2
git status --short                            # ?? bin/
printf 'terminate\n' | java -jar clide.jar /tmp/demo2
git status --short                            # ?? bin/     ← toujours là
```

Taille mesurée : **148 Mo** sur PlantUML, 16 Ko sur le projet jouet.
`.clide/tmp/` (20 Mo sur PlantUML) est en revanche correctement auto-masqué par
le `.gitignore` contenant `*` que clide y dépose.

#### Correctif proposé, et **testé** : déplacer la sortie sous `.clide/tmp/bin`

Suggestion de l'auteur du projet, vérifiée expérimentalement plutôt que
raisonnée. Le dossier de sortie n'est écrit qu'à un seul endroit,
`EclipseDescriptorBuilder` (`src/main/java/clide/jdtls/EclipseDescriptorBuilder.java`,
lignes 110 et 127) :

```diff
-  <classpathentry kind="output" path="bin/main"/>
+  <classpathentry kind="output" path=".clide/tmp/bin/main"/>

-  <classpathentry kind="src" output="bin/test" path="%s">
+  <classpathentry kind="src" output=".clide/tmp/bin/test" path="%s">
```

> **Modification locale, non commitée.** Tout ce qui suit est du comportement
> observé **après cette modification de deux lignes**, `ant dist` refait ;
> tout le reste du rapport décrit le code d'origine. Le source et le jar ont été
> restaurés à l'identique en fin de test (§8.4).

Ce qui a été vérifié :

| Vérification | Résultat |
|---|---|
| `git status` sur un dépôt neutre (ne gitignorant pas `bin`) | **vide**, pendant que le daemon tourne comme après `terminate` — la promesse de `CLAUDE.md` devient vraie |
| Où atterrissent les `.class` | `.clide/tmp/bin/main/…` — auto-masqué par le `.gitignore` (`*`) que clide y dépose déjà |
| Détection des changements (`rebuild`) | intacte : `.clide` **est déjà** dans `FilesRepository.SKIPPED_DIRECTORIES` (`FilesRepository.java:20`), donc le scan ne walke pas les 188 (clide) / ~30 000 (PlantUML) `.class` |
| `rebuild` sur clide | `0 file(s) changed … rebuilt in 3120 ms`, 0 erreur — inchangé |
| `run_tests` sur clide | **520 tests**, identique à avant le patch : `ProjectTests.outputFolders()` dérive du classpath que jdtls rapporte, il suit tout seul |
| `run_test` ciblé sur clide | `10 test(s), 10 passed` — inchangé |
| `run_tests` sur PlantUML | **3065 tests, 2978 passed, 79 failed, 8 skipped** — strictement identiques aux chiffres du §8.3 |
| Racine de PlantUML | plus de `bin/` ; les 148 Mo sont sous `.clide/tmp/bin` |
| Coût au démarrage à froid sur PlantUML | **aucun** : patché 50,7 s / 45,6 s, origine 45,6 s / 46,1 s (2 mesures chacun, `.clide/tmp` pré-peuplé dans les quatre cas) — l'écart est dans le bruit d'une machine à 2 cœurs |
| Interaction avec `MULTI_MODULE_PROJECT` (§3.6) | **neutre** : toujours exactement `2 modules`, pas 3. Le patch ne l'aggrave pas, et ne le corrige pas non plus |
| Suite unitaire de clide | **1 seul test à mettre à jour** : `EclipseDescriptorBuilderTest.testFoldersAreMarkedAndProductionFoldersAreNot` (`src/test/java/clide/jdtls/EclipseDescriptorBuilderTest.java:73`) épingle la chaîne littérale `output="bin/test"`. 499/500 passent sans y toucher |

**Conclusion : la piste est la bonne, et la surface est minuscule** — deux
chaînes dans un seul fichier, plus une assertion de test. Aucun autre point du
code n'a besoin d'être touché, précisément parce que les deux endroits qui
auraient pu poser problème sont déjà bien écrits : `.clide` est déjà exclu du
scan de fichiers, et `ProjectTests` demande les dossiers de sortie à jdtls au
lieu de les coder en dur.

Deux réserves, honnêtement séparées de ce qui a été mesuré :

- **Non testé** : un projet qui possède déjà ses propres `.project`/`.classpath`
  garde les siens (clide les restaure), donc le patch est sans effet sur lui —
  il continuera d'écrire là où son `.classpath` le dit. C'est le comportement
  correct, mais cela veut dire que la promesse « rien ne bouge à la racine »
  reste dépendante du projet dans ce cas-là.
- **Hypothèse, non vérifiée** : entasser davantage sous `.clide/tmp/` va dans le
  sens inverse du correctif naturel du `MULTI_MODULE_PROJECT` cité par
  `CLAUDE.md` (« excluding `.clide/**` from jdtls's import scan »). Si cette
  exclusion est un jour implémentée, elle devra couvrir `bin` sans exclure les
  jars de `.clide/tmp/jar-junit/` qui, eux, doivent rester sur le classpath.
  Les deux chantiers gagneraient à être pensés ensemble.

### 4.9 Les commandes de transaction (point 20) — toujours débranchées

```
?ERROR UNKNOWN_KEYWORD: Unknown command 'open_transaction'
hint: one token per line - a whole command written on a single line reads as one keyword
```

Les six commandes sont commentées, mais **plus dans `Main.java` comme l'indique
`TESTS.md`** : elles ont migré dans
`src/main/java/clide/CommandRepository.java:74-79`. À corriger dans le suivi.

Conséquence en cascade : `terminate` documenté comme « refuses if a transaction
is still open » ne peut jamais refuser, et le `hint:` ci-dessus est trompeur
(il suggère une faute de protocole alors que la commande n'existe pas).

### 4.10 Point 6 — `@Help` de `man` toujours placeholder

```
man <Keyword> - please write help of man
```

Toujours dans la sortie de `help`. Curieusement, la page `man man` elle-même est
écrite, complète et bonne — c'est uniquement la ligne d'une ligne qui manque.

### 4.11 Point 13 — `rebuild` sans changement paie le build complet

| Projet | `rebuild` à 0 changement |
|---|---|
| clide (137 fichiers) | 3,32 s / 3,03 s / 2,89 s |
| PlantUML (3633 fichiers) | 9,25 s / 10,22 s / 9,24 s |

Le message est honnête (`0 file(s) changed since the last build, rebuilt in
9254 ms`), donc rien n'est mensonger — mais le coût est bien payé.

---

## 5. Contre-vérifications clide ↔ `grep`

### 5.1 Le refactor `UDrawable.drawU` — dans les deux sens

Ensemble comparé : les **456 fichiers** que clide déclare en erreur après le
changement de signature, contre les **532 fichiers** où `grep -rl "drawU"`
trouve quelque chose.

| Direction | Fichiers | Verdict |
|---|---|---|
| `grep` les voit, clide ne les signale pas | **83** | **clide a raison sur les 83** |
| clide les signale, `grep` ne les voit pas | **7** | **clide a raison sur les 7** |

**Les 83 que `grep` ajoute à tort**, classés un par un :

- **36** ne contiennent aucun appel réel : `drawUInternal(...)` (autre méthode),
  du code commenté (`// ud.drawU(ug.apply(translate));`), ou une mention en
  commentaire (`// Idea copied from … ComponentTextArrow.drawU()`).
- **42** déclarent ou appellent un `drawU` **homonyme d'une autre interface**,
  avec une signature différente : `Component.drawU(UGraphic, Area, Context2D)`
  (20 classes `asciiart`), `Element.drawU(UGraphic, int, XDimension2D)`
  (14 classes `salt`), `ISvgSpriteParser.drawU(UGraphic, double, HColor, HColor)`,
  `Emoji.drawU(UGraphic, double, HColor)`, etc.
- **4** déclarent bien un `void drawU(UGraphic ug)` de même arité, mais sur une
  classe qui **n'implémente pas `UDrawable`** : `Grid2` (`class Grid2 {`),
  `RoundedContainer` (`final class RoundedContainer {`), `TimingNote`
  (`class TimingNote {`), et `GtileWhile` dont les trois occurrences sont…
  commentées.
- **1** est `UDrawable.java` lui-même : l'interface modifiée, dont la
  déclaration est évidemment correcte et ne produit donc aucune erreur.

**Les 7 que `grep` rate**, et qui sont le cœur du sujet — ces fichiers ne
contiennent **nulle part** la chaîne `drawU`, et pourtant ils ne compilent plus :

```
src/main/java/net/sourceforge/plantuml/activitydiagram3/ftile/FtileDecorateWelding.java:44
  → public class FtileDecorateWelding extends FtileDecorate {
    [error] The type FtileDecorateWelding must implement the inherited abstract method
            UDrawable.drawU(UGraphic, boolean)
```

Idem pour `FtileWithSwimlanes`, `FtileDecorateIn`, `FtileDecorateOut`,
`FtileDecoratePointOut` (tous `extends FtileDecorate`) et `BodyEnhanced1`,
`BodyEnhanced2` (tous deux `extends BodyEnhancedAbstract`). L'obligation
d'implémenter arrive par héritage, à deux niveaux de distance du mot cherché.

**Bilan quantitatif** : sur ce refactor, `grep` produit 83 faux positifs et
7 faux négatifs. Un développeur qui ferait confiance à `grep -rl drawU` visiterait
83 fichiers pour rien et **oublierait 7 fichiers qui ne compilent plus**.

### 5.2 `find_reference` sur une méthode à homonyme — `DiagramType.getStyleName()`

Deux méthodes `getStyleName()` sans lien existent dans PlantUML :
`DiagramType.getStyleName()` et `AbstractEntityImage.getStyleName()`.

| Source | Occurrences |
|---|---|
| `grep -rnw getStyleName` (naïf) | **41** |
| dont déclarations | 2 |
| dont appels pointés `.getStyleName()` (tous sur un `DiagramType`) | 27 |
| dont appels nus `getStyleName()` (tous sur `AbstractEntityImage`, dans `svek/image/`) | 12 |
| **`find_reference method` sur `DiagramType.getStyleName`** | **27** |

Les 27 de clide et les 27 appels pointés coïncident **fichier par fichier**
(17 fichiers de part et d'autre, `comm` vide dans les deux sens). Les 12 appels
nus, qui vont vers l'autre méthode, sont correctement exclus, et les
2 déclarations aussi.

Verdict : clide 27/27 exact, `grep` 14 faux positifs sur 41 (34 %).

### 5.3 Code mort — `DiagramType.isLegacyUML()`

Le script Lua du §3.7 annonce **0 référence**. `grep -rn "isLegacyUML" src`
renvoie **une seule ligne** : la déclaration elle-même. Les deux sources sont
d'accord, et `find_reference` excluant la déclaration par contrat, la réponse
« 0 » est la bonne : c'est de la méthode publique morte, trouvée sans effort.

### 5.4 `find_implementation` sur clide — l'ensemble complet

`find_implementation type` sur `clide.core.Command` : 24 fichiers.
`grep -rlE "extends Command |extends DisconnectCommand "` : 24 fichiers.
`comm` vide dans les deux sens. Cas facile (l'héritage est ici littéral dans le
texte) — il sert de contrôle négatif : quand `grep` *peut* répondre, clide ne
dévie pas.

---

## 6. Tests libres

### 6.1 Mission de navigation : « comment PlantUML choisit-il le type de diagramme ? »

Contrainte : répondre uniquement avec clide, sans ouvrir un fichier.
**Résolu en 4 commandes**, chaque sortie recollée sans retouche dans la suivante :

1. `find_symbol DiagramType`
   → `[enum] …:src/main/java/net/sourceforge/plantuml/core/DiagramType.java:43:13:DiagramType`
2. `list_members <position ci-dessus>` → 46 membres, dont 6 méthodes ; la
   candidate saute aux yeux : `findStartTypes(CharSequence)` ligne 69.
3. `find_reference method …:69:40:findStartTypes` → 54 sites, dont **6 en
   production** :
   `PSystemBuilder.java:210` et `:239`, `UmlSource.java:165` et `:174`,
   `PSystemErrorPreprocessor.java:51`, `ReadFilterMergeLines.java:84`
   — et 48 dans `DiagramTypeTest`, qui donne au passage la table de vérité
   complète (`@startuml`, `@startgantt`, `@STARTJSON`, `\startuml`, `@startxyz`…).
4. `search_regex src/main/java/net/sourceforge/plantuml PSystemBuilder\.java types.contains`
   → `PSystemBuilder.java:211 : UmlSource.createWithRaw(source, types.contains(DiagramType.SEQUENCE), rawSource)`

Réponse : la première ligne de la source est passée à
`DiagramType.findStartTypes()`, qui rend une `Collection<DiagramType>` ;
`PSystemBuilder` s'en sert pour construire l'`UmlSource` puis choisir la
fabrique. **Zéro retouche de position sur les 4 étapes** — c'est le point de
design central de l'outil et il tient.

Ce qui a coûté : à l'étape 2, les 46 membres sortent en une liste de 46 lignes
dont 39 constantes d'enum ; trouver la méthode utile demande de lire toute la
sortie. Un filtre par `kind` sur `list_members` serait le complément naturel
(à rapprocher de `summarize_package` dans `TODO.md`).

### 6.2 Audit « quelles méthodes n'ont aucun appelant » (mode `--lua`)

Voir §3.7 et §5.3 : un script de 7 lignes, 1,4 s, une méthode publique morte
trouvée sur le premier type essayé. C'est le meilleur rapport
résultat/effort de toute la campagne, et c'est aussi la fonctionnalité la moins
documentée hors de `CLAUDE.md` (elle n'apparaît pas dans `help`, étant un
drapeau de ligne de commande et non une commande — comportement correct, mais
qui la rend invisible à qui découvre l'outil par `help`).

Deux frictions relevées, mineures :

- `member.name` rend `"isLegacyUML()"`, parenthèses comprises — l'exemple de
  `CLAUDE.md` (`print(member.name, refs.locations.totalCount)`) laisse attendre
  un nom nu.
- `--lua` n'est mentionné nulle part dans `help` ni dans aucune page `man`.

### 6.3 Pistes qui n'ont rien donné

Signalées parce que « rien trouvé » est aussi une information :

- **Position périmée** : `?ERROR FILE_MODIFIED` déclenché correctement à chaque
  tentative, y compris quand l'édition était **ailleurs dans le fichier** que la
  ligne visée (comportement voulu et documenté). Aucune faille trouvée.
- **`set_max_results` aux bornes** : `0`, `10000`, `10001`, `-1`, `abc` — tous
  traités exactement comme `CLAUDE.md` l'annonce, message et `hint:` compris.
- **`terminate` avec quelque chose d'ouvert** : intestable, les transactions
  étant débranchées (§4.9). `TERMINATE_REFUSED` est du code mort en pratique.
- **Concurrence de daemons** : trois projets ouverts simultanément
  (`/tmp/demo`, `/tmp/clide`, `/tmp/plantuml`), chacun sur son port, aucun
  parasitage, aucune confusion de projet. Rien à signaler.
- **Cache jdtls depuis plusieurs CWD** : voir §8.1, aucun défaut trouvé.
- **`--human` en pipe** : fonctionne, les prompts `> READY` / `> Name ?`
  s'intercalent proprement sans polluer les résultats. Rien à signaler.

---

## 7. Écarts entre la documentation et le code

| # | `CLAUDE.md` dit | Observé |
|---|---|---|
| a | « `find_declaration type` vers un type JDK … **a roughly 30 s timeout instead of a clear error** » | L'erreur **est** claire et nommée : `?ERROR JDTLS_REQUEST_FAILED: … No response for textDocument/typeDefinition (id=37) after 30s`. Seul le coût de 30 s subsiste. Doc en retard sur le code. |
| b | Section entière « Transactions », « The transaction mechanism below **exists and works** », 6 commandes | Aucune n'est enregistrée (`CommandRepository.java:74-79`) → `?ERROR UNKNOWN_KEYWORD`. Point 20. |
| c | « `terminate` … refuses if a transaction is still open » | Inatteignable, conséquence de (b). |
| d | « On a very large suite with missing external dependencies, `run_tests` **may never finish** » | 3065 tests en **20,9 s** sur PlantUML. `TESTS.md` point 15 demandait déjà le retrait de cet avertissement ; il est toujours là. |
| e | « subsequent launches are fast (**~0,25 s** per session) » | 0,090 – 0,103 s mesurés (3 essais). Le chiffre est pessimiste d'un facteur 2,5. |
| f | « Measured cost on a project the size of PlantUML : **9 to 12 s** » pour `rebuild` | 9,2 – 10,7 s mesurés (5 essais, avec et sans changement). **Conforme** — c'est le point 21 de C5 (qui mesurait 14-21 s) qui est désormais périmé, pas la doc. |
| g | « `.project`/`.classpath` … **erased once the initial build finishes** » | Vérifié : les deux ont disparu de la racine de PlantUML **pendant** que le daemon tourne. Corrigé depuis C5. |
| h | « a `git status` on the opened project **never shows anything moving at its root** because of clide » | Faux : `bin/` reste, avant et après `terminate` (§4.8). Vrai pour clide et PlantUML seulement parce qu'ils gitignorent `bin`. |
| i | `run_test` « the whole class if `<position>` names the test class, **that single method otherwise** » | Vrai pour une méthode sans paramètre d'une classe de test non imbriquée. Faux pour `@ParameterizedTest` (§4.1) et pour toute méthode d'une classe `@Nested` (§4.3). |
| j | `--lua` documenté dans `CLAUDE.md` | Absent de `help` et d'`man`. Correct sur le fond (c'est un drapeau CLI), mais invisible à qui découvre l'outil par `help`. |

Écarts avec `AGENT-TESTING.md` lui-même (chiffres à rafraîchir) :
`ant dist` 9 s → **7,0 s** ; `ant test` 435 tests → **500 tests** ;
« clide … peut posséder ses propres fichiers Eclipse » → le checkout n'en a pas,
il faut les créer pour reproduire `MULTI_MODULE_PROJECT` (§3.6).

---

## 8. État des points de `TESTS.md`

### 8.1 Point 19 — revalidation détaillée (**corrigé**)

Le seul point que C5 déclarait corrigé, donc le seul à revalider intégralement.
Quatre vérifications, toutes concluantes :

1. **Chemin annoncé au démarrage** :
   `(2/4) Initializing IDE ... [OK] (jdtls: /root/.cache/clide/jdtls-ba495e18)`.
2. **CWD jamais pollué** : clide lancé depuis `/tmp/run`, `/tmp/run2`, `/tmp/run3`
   sur le même projet ; les trois répertoires sont **restés vides**
   (`ls -a` → `.` et `..` seulement), et le cache ne contient **qu'un seul**
   `jdtls-*`.
3. **`CLIDE_JDTLS_HOME` pris verbatim** : avec
   `CLIDE_JDTLS_HOME=/tmp/custom-jdtls`, le daemon annonce
   `(jdtls: /tmp/custom-jdtls)` — pas de suffixe d'empreinte ajouté.
4. **L'empreinte invalide bien le cache** : un fichier anodin ajouté à
   `jdt-language-server-latest.zip`, `ant dist` refait, et le daemon suivant
   résout vers `/root/.cache/clide/jdtls-78efb974` — **un nouveau répertoire**,
   l'ancien laissé en place, inerte. Archive et jar restaurés ensuite
   (`git checkout --` + jar d'origine remis, md5 vérifié).

### 8.2 Tableau de synthèse

| # | Point | Statut C5 | **Statut C6** | Ce qui le fait dire |
|---|---|---|---|---|
| 5 | `typeDefinition` vers un type JDK : timeout 30 s | ouvert | **toujours ouvert** | 30,094 s mesurés ; erreur nommée `JDTLS_REQUEST_FAILED` (§4.5) |
| 6 | `@Help` de `man` : placeholder | ouvert | **toujours ouvert** | `man <Keyword> - please write help of man` dans `help` (§4.10) |
| 9 | Call hierarchy | ouvert | **toujours ouvert** | aucune commande de ce type dans `help` (17 commandes listées) |
| 10 | Type hierarchy structurée / super-types | ouvert | **toujours ouvert** | idem ; `find_implementation` descend, rien ne remonte |
| 11 | `list_members` avec membres hérités | ouvert | **toujours ouvert** | `list_members` sur `EmbeddedDiagram extends TextBlockMemoized` : 24 membres, **tous** dans `EmbeddedDiagram.java`, aucun du parent |
| 12 | Recherche de champs par nom | limite jdtls | **inchangé** | non re-testé, limite jdtls non actionnable |
| 13 | `rebuild` à 0 changement paie le build complet | ouvert | **toujours ouvert** | 9,2-10,2 s sur PlantUML, 2,9-3,3 s sur clide, à 0 fichier changé (§4.11) |
| 14 | Compilation des tests sans JUnit dans `.clide/` | corrigé | **confirmé corrigé** | 0 erreur de compilation sur les deux projets, `resource/vendor-junit/` présent dans le jar |
| 15 | `run_tests` ne finit jamais | levé | **confirmé levé** | 3065 tests en 20,9 s sur PlantUML |
| 16 | `run_test` sur méthode à paramètres | ouvert (P1) | **toujours ouvert (P1)** | reproduit sur clide et sur PlantUML, cause confirmée par fork manuel (§4.1) |
| 17 | Message d'échec mangé par la bannière JVM | ouvert (P2) | **toujours ouvert, aggravé** | `firstLine` inchangé (`ProjectTests.java:468`) ; le message *contient* la bannière et se fait donc filtrer (§4.2) |
| 18 | `ABORTED` compté `failed` | ouvert | **toujours ouvert** | cobaye minimal + 5 cas réels sur PlantUML (§4.4) |
| 19 | `jdtls/` extrait dans le CWD | corrigé | **confirmé corrigé** | 4 vérifications, §8.1 |
| 20 | Transactions documentées mais débranchées | ouvert | **toujours ouvert** | `?ERROR UNKNOWN_KEYWORD` ; **la référence source a changé** : `CommandRepository.java:74-79`, plus `Main.java:61-62` (§4.9) |
| 21 | `rebuild` annoncé 9-12 s (mesuré 14-21 s) ; `.project` retiré seulement au `terminate` | ouvert | **corrigé** | 9,2-10,7 s mesurés, donc conformes à la doc ; `.project` et `.classpath` absents de la racine pendant que le daemon tourne (§7 f, g) |
| **22** | `run_test` sur une classe/méthode `@Nested` → `TEST_RUNNER_BROKEN` | — | **nouveau** | §4.3 |
| **23** | `run_tests all` : les échecs tombent hors de la troncature à 100 | — | **nouveau** | §4.6 |
| **24** | `run_tests` sur clide ramasse le package `fixture` → suite rouge sur dépôt intact | — | **nouveau** | §4.7 |
| **25** | `bin/` (148 Mo) laissé à la racine du projet après `terminate` | — | **nouveau** — correctif proposé **testé, sans régression** | §4.8 |

Aucun point n'est resté **non testé**.

### 8.3 La suite complète de PlantUML, décomposée honnêtement

```
?ERROR TEST_FAILURES: 79 test(s) failed out of 3065
run_tests: 3065 test(s), 2978 passed, 79 failed, 8 skipped in 20856 ms
```

| Cause des 79 « échecs » | Nombre | Nature |
|---|---|---|
| `java.awt.HeadlessException` | 70 | **environnemental** — pas de X11 dans le sandbox (tout `net.sourceforge.plantuml.cli`) |
| `org.opentest4j.TestAbortedException` | 5 | **faux échec de clide** — point 18 (4 × `VegaTest` « allow-failure: true », 1 × garde réseau) |
| `ClassNotFoundException: org.eclipse.elk.graph.util.ElkGraphUtil` | 3 | **environnemental** — ELK absent du classpath |
| `java.io.IOException: Cannot open URL https://plantuml.com` | 1 | **environnemental** — pas de réseau |

**Zéro échec réel.** Sur un checkout intact de PlantUML dans cet environnement,
clide rapporte 79 échecs dont 74 environnementaux et 5 imputables au point 18.

**Réécriture de fichiers pendant `run_tests`** — confirmée, et c'est bien
PlantUML qui la fait, pas clide (`VegaTest` régénère ses résumés) :

```
$ git status --short          # avant : vide
$ ... run_tests ...
$ git status --short
 M src/test/resources/vega/vega-summary.md
 M src/test/resources/vega/vega-summary.txt
 M src/test/resources/vega/vega.json
```

md5 des trois fichiers modifiés avant/après, vérifié. Restauré par
`git checkout -- src/test/resources/vega/`. À signaler à qui automatise
`run_tests` sur PlantUML : la commande n'est pas en lecture seule pour le dépôt.

### 8.4 Hygiène de fin de campagne

- `terminate` (pas seulement `exit`) exécuté sur les quatre daemons ouverts
  (`/tmp/demo`, `/tmp/demo2`, `/tmp/clide`, `/tmp/plantuml`). Aucun processus
  `clide.jar` ne survit (`pgrep -af clide.jar` vide).
- **`git status --short` vide sur les deux dépôts.**
- Fichiers introduits pour les tests et **supprimés** :
  `src/test/java/fixture/AbortedByAssumption.java` (cobaye du point 18),
  `.project` et `.classpath` créés à la racine de clide pour reproduire
  `MULTI_MODULE_PROJECT` (§3.6).
- Fichiers modifiés pour les tests et **restaurés à l'identique** :
  `UrlBuilder.java` et `UDrawable.java` (PlantUML),
  `src/test/resources/vega/*` (réécrits par PlantUML lui-même),
  `jdt-language-server-latest.zip` et `clide.jar` (test d'empreinte du §8.1).
- **Une seule modification du source de clide**, et elle est déclarée : les
  deux chaînes de `EclipseDescriptorBuilder` du §4.8, faites pour valider le
  correctif proposé du point 25. Fichier restauré depuis sa sauvegarde et
  `clide.jar` d'origine remis en place ; `git status` le confirme. **Tout le
  reste du rapport décrit le comportement du code d'origine** — les diagnostics
  de cause des points 16, 17, 18 et 22 ont été obtenus par lecture du source et
  par exécution manuelle de `clide.test.TestRunnerMain` sur le classpath du
  projet, sans rien toucher.
- Reste à la racine des projets : `bin/` (voir §4.8) et `.clide/tmp/`
  (auto-masqué). `bin/` supprimé à la main sur clide.

---

## 9. Ordre de correction proposé

1. **Point 16 — `run_test` sur une méthode à paramètres.** Inchangé depuis C5 et
   toujours en tête pour la même raison : c'est la priorité n°2 annoncée du
   projet (« lancer un test ciblé ») et elle est indisponible sur une large part
   des suites JUnit 5 modernes. Le correctif proposé au §4.1 (sélectionner la
   classe, filtrer après découverte) **règle aussi le point 22**, ce qui en fait
   le meilleur rapport valeur/effort de la liste.

2. **Point 22 — `run_test` sur une classe `@Nested`.** Immédiatement derrière
   parce qu'il partage le correctif, et parce qu'ensemble les deux points
   couvrent la quasi-totalité des cas où `run_test` échoue aujourd'hui sur un
   projet réel.

3. **Point 17 — la bannière JVM masque l'exception.** Deux lignes, et le gain ne
   se mesure pas en confort mais en heures : les points 16 et 22 ont chacun
   demandé de relancer le fork à la main pour obtenir la seule ligne qui
   comptait. Aggravation constatée en C6 : le message contenant la bannière, il
   se fait supprimer par le filtre naturel — un agent voit alors une sortie vide
   et diagnostique un tout autre bug (c'est arrivé en C5 *et* en C6, en sens
   inverse).

4. **Point 18 — `ABORTED` compté `failed`.** Le compteur `skipped` existe déjà et
   `executionSkipped` l'alimente correctement ; il ne manque qu'une branche
   `ABORTED` dans `executionFinished`. 5 faux échecs permanents sur PlantUML,
   c'est-à-dire 5 raisons pour un agent de croire qu'il a cassé quelque chose.

5. **Point 25 — `bin/` laissé à la racine.** 148 Mo dans le dépôt de
   l'utilisateur, contre une promesse explicite de `CLAUDE.md`. Placé plus haut
   que les points de documentation parce que c'est le seul qui laisse une trace
   physique chez l'utilisateur — et parce que le correctif est **déjà écrit et
   validé** : deux chaînes dans `EclipseDescriptorBuilder` pour envoyer la sortie
   dans `.clide/tmp/bin`, plus une assertion de test à mettre à jour. Mesuré sans
   régression sur PlantUML (3065 tests identiques, démarrage à froid inchangé) —
   voir le tableau du §4.8. C'est le meilleur rapport effet/risque de toute la
   liste.

6. **Point 23 — les échecs hors fenêtre de troncature.** Trier échecs d'abord,
   ou ajouter un `hint:` vers `run_tests failures`. Peu de code, et cela évite de
   relancer une suite entière pour lire une ligne.

7. **Écarts de documentation (points 20, 21 et §7).** Réaligner `CLAUDE.md` :
   retirer l'avertissement « `run_tests` may never finish » (point 15 levé
   depuis C5), corriger « instead of a clear error » pour le point 5, corriger
   « ~0.25 s » en « ~0,1 s », et **trancher sur les transactions** — soit les
   réactiver dans `CommandRepository.java:74-79`, soit dire dans `CLAUDE.md`
   que la section décrit du code présent mais non branché. Mettre aussi à jour
   la référence source du point 20 dans `TESTS.md` (`Main.java:61-62` →
   `CommandRepository.java:74-79`).

8. **Point 5 — 30 s de timeout sur un type JDK.** L'erreur étant désormais
   propre et nommée, et `hover` répondant instantanément, il ne reste qu'un coût
   d'attente sur un cas de bord. Un court-circuit côté clide (ne pas envoyer
   `typeDefinition` quand le type résout dans `java.*`) suffirait.

9. **Point 24 — périmètre de `run_tests` (`--scan` vs build).** Placé en fin de
   liste non parce qu'il est bénin, mais parce que c'est une **décision de
   conception**, pas un bug : soit `--scan` reste la règle et il faut le
   documenter, soit clide apprend à lire un périmètre. À trancher avant de coder.

10. **Points 6, 9, 10, 11, 13.** Confort et fonctionnalités souhaitées :
    la ligne `@Help` de `man` (une chaîne), la call hierarchy et la type
    hierarchy (jdtls sait les faire, `TODO.md` les a en ligne de mire), les
    membres hérités en option, et le raccourci de `rebuild` à 0 changement.
    Aucun ne bloque un usage réel aujourd'hui.

---

### Ce que la campagne a cherché et n'a pas trouvé

Pour que « rien trouvé » ait un poids là où il apparaît : la campagne a envoyé
environ 90 commandes clide réparties sur 4 daemons, mené deux
contre-vérifications systématiques `grep`/clide dans les deux sens (532 et
41 occurrences examinées une par une, chaque écart tranché nominativement),
introduit et retiré 3 modifications de source dont un refactor cassant à
1034 erreurs, passé 13 cas limites de protocole, et revalidé les 4 propriétés du
point 19. Les commandes sémantiques (`find_*`, `hover`, `list_members`,
`search_regex`) et `rebuild` **n'ont pas produit une seule réponse fausse** : les
90 écarts avec `grep` sont, sans exception, imputables à `grep` — 83 faux
positifs d'un côté, 7 fichiers manqués de l'autre. Les quatre
problèmes nouveaux sont tous dans `run_test`/`run_tests` ou dans la propreté du
dépôt — jamais dans le cœur sémantique.
