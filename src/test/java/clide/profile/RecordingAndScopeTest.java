package clide.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Les options que Recording donne aux JVM de test, et ce que ProfileScope appelle « le projet ». */
class RecordingAndScopeTest {

	private static void touch(final Path root, final String relative) throws IOException {
		final Path file = root.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "");
	}

	@Test
	@DisplayName("sans profil, aucune option n'est ajoutée à la JVM de test")
	void noRecordingAddsNothing() {
		assertEquals(List.of(), Recording.none().nextOptions());
		assertEquals(List.of(), Recording.none().files());
	}

	@Test
	@DisplayName("les options sont celles du prototype : 1 ms, profondeur 256, écriture à la sortie")
	void optionsAreThePrototypes(@TempDir final Path root) throws IOException {
		final Recording recording = Recording.start(root);

		assertEquals(List.of(
				"-XX:StartFlightRecording=filename=.clide/tmp/profiles/profile-0.jfr,settings=profile,"
						+ "jdk.ExecutionSample#period=1ms,dumponexit=true",
				"-XX:FlightRecorderOptions=stackdepth=256"), recording.nextOptions());
	}

	@Test
	@DisplayName("chaque JVM forké a son fichier : deux dossiers de test, deux enregistrements")
	void everyForkGetsItsOwnFile(@TempDir final Path root) throws IOException {
		final Recording recording = Recording.start(root);

		assertTrue(recording.nextOptions().get(0).contains("profile-0.jfr"));
		assertTrue(recording.nextOptions().get(0).contains("profile-1.jfr"));
	}

	@Test
	@DisplayName("files() ne rend que les fichiers réellement écrits : un JVM tué n'en laisse pas")
	void onlyWrittenFilesCount(@TempDir final Path root) throws IOException {
		final Recording recording = Recording.start(root);
		recording.nextOptions();
		recording.nextOptions();
		touch(root, ".clide/tmp/profiles/profile-1.jfr");

		assertEquals(List.of(root.resolve(".clide/tmp/profiles/profile-1.jfr")), recording.files());
	}

	@Test
	@DisplayName("un nouvel enregistrement efface ceux du précédent, et seulement les .jfr")
	void startForgetsThePreviousRecordings(@TempDir final Path root) throws IOException {
		touch(root, ".clide/tmp/profiles/profile-0.jfr");
		touch(root, ".clide/tmp/profiles/profile-7.jfr");
		touch(root, ".clide/tmp/profiles/notes.txt");

		Recording.start(root);

		assertFalse(Files.exists(root.resolve(".clide/tmp/profiles/profile-0.jfr")));
		assertFalse(Files.exists(root.resolve(".clide/tmp/profiles/profile-7.jfr")));
		assertTrue(Files.exists(root.resolve(".clide/tmp/profiles/notes.txt")));
	}

	@Test
	@DisplayName("par défaut le projet, ce sont les sources de production : le code de test est replié")
	void testSourcesAreFoldedByDefault(@TempDir final Path root) throws IOException {
		touch(root, "src/main/java/demo/Calc.java");
		touch(root, "src/test/java/demo/CalcTest.java");

		final ProfileScope main = ProfileScope.of(root, false);

		assertEquals("src/main/java/demo/Calc.java", main.sourceOf("demo.Calc"));
		assertNull(main.sourceOf("demo.CalcTest"));
	}

	@Test
	@DisplayName("avec includeTests, le code de test compte aussi")
	void includeTestsCountsTheTests(@TempDir final Path root) throws IOException {
		touch(root, "src/main/java/demo/Calc.java");
		touch(root, "src/test/java/demo/CalcTest.java");

		final ProfileScope all = ProfileScope.of(root, true);

		assertEquals("src/test/java/demo/CalcTest.java", all.sourceOf("demo.CalcTest"));
		assertEquals("src/main/java/demo/Calc.java", all.sourceOf("demo.Calc"));
	}

	@Test
	@DisplayName("une classe interne se rattache au fichier de sa classe englobante")
	void nestedClassesBelongToTheOuterFile(@TempDir final Path root) throws IOException {
		touch(root, "src/main/java/demo/Calc.java");

		assertEquals("src/main/java/demo/Calc.java", ProfileScope.of(root, false).sourceOf("demo.Calc$Inner$1"));
	}

	@Test
	@DisplayName("une lambda ou une classe cachée n'est jamais au projet, même si son préfixe l'est")
	void hiddenClassesAreNeverTheProjects(@TempDir final Path root) throws IOException {
		touch(root, "src/main/java/demo/Calc.java");
		final ProfileScope scope = ProfileScope.of(root, false);

		assertNull(scope.sourceOf("demo.Calc$$Lambda/0x000001234"));
		assertNull(scope.sourceOf("demo.Calc$$Lambda$14/0x0000000800c03000"));
		assertNull(scope.sourceOf("demo.Calc/0x0000000800c04000"));
	}

	@Test
	@DisplayName("une classe du JDK ou d'une bibliothèque n'est pas au projet")
	void libraryClassesAreNotTheProjects(@TempDir final Path root) throws IOException {
		touch(root, "src/main/java/demo/Calc.java");

		assertNull(ProfileScope.of(root, false).sourceOf("java.util.HashMap"));
	}

}
