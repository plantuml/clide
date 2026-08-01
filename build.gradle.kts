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

repositories {
	mavenCentral()
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
	// les tests. C'est ce que lib/junit-platform-console-standalone apporte au
	// build Ant - ici la version non-repackagée, sinon toutes les classes JUnit
	// se retrouveraient en double dans le jar.
	testRuntimeOnly("org.junit.platform:junit-platform-console:1.10.1")
}

tasks.named<JavaExec>("run") {
	standardInput = System.`in`
}

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
}

tasks.test {
	useJUnitPlatform()
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
 * clide n'a aucune dépendance d'exécution (voir CLAUDE.md) : tout ce qui est
 * absorbé ici vient de testRuntimeClasspath, c'est-à-dire JUnit. Le jour où une
 * dépendance d'exécution apparaît, elle est absorbée sans rien changer.
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
 * Ce fichier n'a pas été exécuté - le sandbox n'atteint pas Maven Central.
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
