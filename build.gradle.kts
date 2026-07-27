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

tasks.named<JavaExec>("run") {
	standardInput = System.`in`
}
