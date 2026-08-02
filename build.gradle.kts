plugins {
	java
	application
	eclipse
}

group = "clide"
version = "0.1"

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(21))
	}
}

application {
	mainClass.set("clide.Main")
}

// flatDir plutôt que mavenCentral() : comme build.xml, ce build ne dépend
// d'aucun accès réseau - toutes les dépendances sont résolues depuis les .jar
// déjà commités dans lib/ (voir scripts/fetch_junit.py et
// scripts/fetch_luajava.py). flatDir fait correspondre chaque coordonnée
// "group:artifact:version[:classifier]" au nom de fichier
// "artifact-version[-classifier].jar" - le group est ignoré, c'est donc à la
// convention de nommage des .jar de lib/ de porter toute l'information.
repositories {
	flatDir {
		dirs("lib")
	}
}

dependencies {
	// clide compile désormais contre la *plateforme* JUnit - et rien qu'elle.
	// clide.test.TestRunnerMain pilote l'API Launcher pour exécuter les tests du
	// projet ouvert (voir la commande run_test), donc le lanceur et le SPI des
	// moteurs sont des dépendances de compilation de clide lui-même, plus de
	// l'outillage de test. compileOnly et non implementation : le fat jar les
	// embarque déjà par testRuntimeClasspath, inutile de les compter deux fois.
	//
	// Jupiter est volontairement absent d'ici : clide n'écrit aucun test JUnit 5
	// dans src/main, et l'exclure empêche qu'un @Test s'importe par accident
	// dans du code de production.
	compileOnly("org.junit.platform:junit-platform-launcher:1.10.1")
	compileOnly("org.junit.platform:junit-platform-engine:1.10.1")
	compileOnly("org.junit.platform:junit-platform-commons:1.10.1")

	//
	// Ces coordonnées reprennent volontairement les versions des .jar commités
	// dans lib/ pour le build Ant (voir scripts/fetch_junit.py) : les deux
	// builds doivent exécuter les mêmes tests avec le même JUnit, sinon un test
	// peut passer d'un côté et pas de l'autre.
	testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
	testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")
	testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
	// Le lanceur en ligne de commande, pour que le fat jar sache aussi exécuter
	// les tests - même artefact que lib/junit-platform-console-standalone côté
	// Ant (voir build.xml) : c'est le seul .jar de ce lib/ à porter le
	// ConsoleLauncher, la version non-repackagée "junit-platform-console" seule
	// n'y étant pas commitée. DuplicatesStrategy.EXCLUDE (voir tasks.jar
	// plus bas) règle les classes qu'il partage avec les .jar discrets déjà
	// listés ci-dessus.
	testRuntimeOnly("org.junit.platform:junit-platform-console-standalone:1.10.1")

	// gudzpoz/luajava, backend natif lua51 (JNI) - voir LUA.md et
	// scripts/fetch_luajava.py. luajava (l'API Lua générique) et lua51 (le
	// binding JNI concret que Main.runLuaScript instancie via "new Lua51()")
	// sont des dépendances de compilation : clide.Main les importe directement.
	implementation("party.iroiro.luajava:luajava:4.1.0")
	implementation("party.iroiro.luajava:lua51:4.1.0")

	// jspecify : annotations de nullabilité référencées par les signatures de
	// luajava. Rien dans clide ne les importe, mais elles doivent rester
	// résolvables au moment de la compilation - et build.xml les embarque aussi
	// dans le fat jar via -explode-libs, donc implementation ici plutôt que
	// compileOnly pour rester cohérent entre les deux builds.
	implementation("org.jspecify:jspecify:1.0.0")

	// lua51-platform (classifier natives-desktop) porte les bibliothèques
	// natives (.so/.dll/.dylib, Windows/Linux/macOS x86/ARM) que jnigen extrait
	// et charge au runtime - jamais référencé depuis du code source, donc
	// runtimeOnly. jnigen-loader/jnigen-commons sont les classes qui font cette
	// extraction (SharedLibraryLoader, détection Os/Architecture) - même chose,
	// jamais importées par clide, seulement par lua51 au runtime.
	runtimeOnly("party.iroiro.luajava:lua51-platform:4.1.0:natives-desktop")
	runtimeOnly("com.badlogicgames.jnigen:jnigen-loader:3.1.1")
	runtimeOnly("com.badlogicgames.jnigen:jnigen-commons:3.1.1")
}

tasks.named<JavaExec>("run") {
	standardInput = System.`in`
}

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
}

tasks.test {
	useJUnitPlatform()
	// Miroir du "--select-package clide" de build.xml : src/test/java/fixture
	// vit hors du package clide précisément pour ne pas être ramassé ici (voir
	// fixture/README.md) - ParameterizedFailing par exemple échoue exprès, il
	// n'est censé être exécuté qu'en sous-processus par
	// TestRunnerMainExecutionTest. Sans ce filtre, "gradle test" échoue
	// systématiquement sur des fixtures qui ne sont pas de vrais tests clide.
	filter {
		includeTestsMatching("clide.*")
	}
	testLogging {
		events("passed", "skipped", "failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}

/*
 * ============================================================================
 * Fat jar - l'équivalent Gradle de la cible "dist" de build.xml
 * ============================================================================
 * Un seul fichier qui lance clide *et* ses tests, sans rien d'autre sur le
 * classpath :
 *
 *     java -jar build/libs/clide.jar <chemin projet>
 *     java -cp build/libs/clide.jar org.junit.platform.console.ConsoleLauncher \
 *          execute --select-package clide
 *
 * clide a maintenant une dépendance d'exécution : luajava/lua51 et leurs
 * bibliothèques natives (voir LUA.md). Comme JUnit, elles arrivent ici via
 * runtimeClasspath / testRuntimeClasspath - rien à changer dans ce bloc quand
 * une dépendance supplémentaire apparaît, tant qu'elle est déclarée plus haut.
 *
 * Trois détails de fusion, les mêmes que côté Ant :
 *
 *   - Multi-Release : junit-platform-commons est un jar multi-release. Ses
 *     classes sous META-INF/versions/ sont ignorées si le jar englobant ne
 *     porte pas lui-même l'attribut.
 *   - module-info.class : un seul descripteur de module peut vivre à la racine
 *     d'un jar. Ignoré sur le classpath, mais garder celui d'une dépendance au
 *     hasard n'a pas de sens.
 *   - signatures : conservées, la JVM refuserait le jar, son contenu ne
 *     correspondant plus à ce qui avait été signé.
 *
 * RESTE À FAIRE, et c'est volontaire : la fusion des META-INF/services. Deux
 * jars déclarant des fournisseurs pour le *même* service doivent voir leurs
 * déclarations concaténées ; ici DuplicatesStrategy.EXCLUDE en garde une seule.
 * Aujourd'hui le seul cas est TestExecutionListener (platform-launcher et
 * platform-reporting) : on y perd un listener de reporting optionnel, pas
 * l'exécution des tests. build.xml, lui, fait la concaténation.
 *
 * Si Gradle doit devenir le build de référence, la vraie réponse n'est pas de
 * réécrire cette fusion à la main mais d'ajouter le plugin Shadow, qui la fait
 * correctement (ServiceFileTransformer) :
 *
 *     plugins { id("com.gradleup.shadow") version "8.3.5" }
 *
 * et de prendre shadowJar à la place de ce bloc.
 *
 * Ce fichier est désormais exécutable hors ligne (flatDir sur lib/, voir plus
 * haut) : validé avec le Gradle système du sandbox
 * (/opt/gradle/bin/gradle --offline), le wrapper (gradlew) restant lui bloqué
 * par l'indisponibilité de services.gradle.org.
 */
tasks.jar {
	archiveFileName.set("clide.jar")
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE

	manifest {
		attributes(
			"Main-Class" to application.mainClass.get(),
			"Multi-Release" to true,
			"Implementation-Title" to project.name,
			"Implementation-Version" to project.version,
		)
	}

	from({
		(configurations.runtimeClasspath.get() + configurations.testRuntimeClasspath.get())
			.filter { it.name.endsWith(".jar") }
			.map { zipTree(it) }
	}) {
		exclude(
			"META-INF/MANIFEST.MF",
			"META-INF/*.SF",
			"META-INF/*.DSA",
			"META-INF/*.RSA",
			"META-INF/*.EC",
			"**/module-info.class",
		)
	}
}
