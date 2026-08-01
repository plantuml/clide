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
	// clide lui-même reste sans dépendance (voir CLAUDE.md) : rien en
	// "implementation". Seuls les tests en ont.
	//
	// Ces coordonnées reprennent volontairement les versions des .jar commités
	// dans lib/ pour le build Ant (voir scripts/fetch_junit.py) : les deux
	// builds doivent exécuter les mêmes tests avec le même JUnit, sinon un test
	// peut passer d'un côté et pas de l'autre.
	testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
	testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")
	testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
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
 * Fat jar : l'équivalent Gradle de la cible "dist" de build.xml. Le jar produit
 * se lance seul (java -jar build/libs/clide.jar), sans rien d'autre sur le
 * classpath.
 *
 * clide n'ayant aujourd'hui aucune dépendance d'exécution, runtimeClasspath est
 * vide et le résultat ne contient que les classes du projet — le jour où une
 * dépendance apparaît, elle est absorbée sans rien changer ici.
 *
 * Les signatures des jars absorbés sont écartées : conservées, la JVM refuserait
 * le jar au motif que son contenu ne correspond plus à ce qui avait été signé.
 */
tasks.jar {
	archiveFileName.set("clide.jar")
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE

	manifest {
		attributes(
			"Main-Class" to application.mainClass.get(),
			"Implementation-Title" to project.name,
			"Implementation-Version" to project.version,
		)
	}

	from({
		configurations.runtimeClasspath.get()
			.filter { it.name.endsWith(".jar") }
			.map { zipTree(it) }
	}) {
		exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
	}
}
