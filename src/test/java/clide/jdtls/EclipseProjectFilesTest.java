package clide.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests d'EclipseProjectFiles - la logique de staging est pure système de
 * fichiers (aucun jdtls ici), donc entièrement testable avec de vrais
 * répertoires temporaires, sans montage.
 *
 * Deux scénarios de fond reviennent partout : un projet qui avait déjà son
 * .project/.classpath (doit ressortir identique après unstage()) et un projet
 * qui n'avait ni l'un ni l'autre (rien ne doit rester après unstage()). Les
 * deux sont vérifiés par comparaison de contenu exact, pas juste "le fichier
 * existe" - un octet différent après un aller-retour serait pire qu'une
 * absence, puisque personne ne le remarquerait.
 */
class EclipseProjectFilesTest {

	private static final String GENERATED_PROJECT = "<projectDescription/>";
	private static final String GENERATED_CLASSPATH = "<classpath/>";

	@Test
	@DisplayName("un .project/.classpath préexistant ressort identique après unstage")
	void preexistingFilesSurviveARoundTrip(@TempDir final Path projectRoot) throws IOException {
		final String originalProject = "<projectDescription>real project</projectDescription>";
		final String originalClasspath = "<classpath>real classpath</classpath>";
		Files.writeString(projectRoot.resolve(".project"), originalProject, StandardCharsets.UTF_8);
		Files.writeString(projectRoot.resolve(".classpath"), originalClasspath, StandardCharsets.UTF_8);

		final EclipseProjectFiles files = EclipseProjectFiles.forProject(projectRoot);
		files.stage(GENERATED_PROJECT, GENERATED_CLASSPATH);

		// Le temps que la session vit, ce sont les fichiers de clide qui sont là.
		assertEquals(GENERATED_PROJECT, Files.readString(projectRoot.resolve(".project")));
		assertEquals(GENERATED_CLASSPATH, Files.readString(projectRoot.resolve(".classpath")));

		files.unstage();

		assertEquals(originalProject, Files.readString(projectRoot.resolve(".project")));
		assertEquals(originalClasspath, Files.readString(projectRoot.resolve(".classpath")));
		assertFalse(Files.exists(projectRoot.resolve(".clide/tmp/.project")),
				"l'original ne doit plus traîner dans le staging une fois restauré");
		assertFalse(Files.exists(projectRoot.resolve(".clide/tmp/.classpath")));
	}

	@Test
	@DisplayName("sans .project/.classpath préexistant, rien ne reste après unstage")
	void generatedFilesLeaveNoTraceWhenNothingPreexisted(@TempDir final Path projectRoot) throws IOException {
		final EclipseProjectFiles files = EclipseProjectFiles.forProject(projectRoot);
		files.stage(GENERATED_PROJECT, GENERATED_CLASSPATH);

		assertTrue(Files.exists(projectRoot.resolve(".project")));
		assertTrue(Files.exists(projectRoot.resolve(".classpath")));

		files.unstage();

		assertFalse(Files.exists(projectRoot.resolve(".project")),
				"aucun .project n'existait avant clide - il ne doit pas en rester un après");
		assertFalse(Files.exists(projectRoot.resolve(".classpath")));
	}

	@Test
	@DisplayName("la copie de debug est écrite dans les deux cas, et jamais restaurée depuis")
	void debugCopyIsAlwaysWrittenAndNeverRestoredFrom(@TempDir final Path projectRoot) throws IOException {
		Files.writeString(projectRoot.resolve(".project"), "real", StandardCharsets.UTF_8);
		// pas de .classpath préexistant, pour couvrir les deux branches d'un coup

		EclipseProjectFiles.forProject(projectRoot).stage(GENERATED_PROJECT, GENERATED_CLASSPATH);

		assertEquals(GENERATED_PROJECT, Files.readString(projectRoot.resolve(".clide/tmp/.project.clide")));
		assertEquals(GENERATED_CLASSPATH, Files.readString(projectRoot.resolve(".clide/tmp/.classpath.clide")));
	}

	@Test
	@DisplayName("unstage() sans stage() préalable ne fait rien")
	void unstageWithoutStageIsANoOp(@TempDir final Path projectRoot) throws IOException {
		Files.writeString(projectRoot.resolve(".project"), "real", StandardCharsets.UTF_8);

		EclipseProjectFiles.forProject(projectRoot).unstage();

		assertEquals("real", Files.readString(projectRoot.resolve(".project")),
				"un unstage() qui n'a jamais eu de stage() ne doit toucher à rien");
	}

	@Test
	@DisplayName("stage() deux fois sans unstage() entre les deux est refusé")
	void stagingTwiceWithoutUnstagingIsRejected(@TempDir final Path projectRoot) throws IOException {
		final EclipseProjectFiles files = EclipseProjectFiles.forProject(projectRoot);
		files.stage(GENERATED_PROJECT, GENERATED_CLASSPATH);

		assertThrows(IllegalStateException.class, () -> files.stage(GENERATED_PROJECT, GENERATED_CLASSPATH));
	}

	@Test
	@DisplayName("un staging propre ne déclenche pas refuseIfDirty")
	void refuseIfDirtyIsSilentOnAFreshProject(@TempDir final Path projectRoot) throws IOException {
		EclipseProjectFiles.refuseIfDirty(projectRoot); // ne doit pas lever

		Files.writeString(projectRoot.resolve(".project"), "real", StandardCharsets.UTF_8);
		final EclipseProjectFiles files = EclipseProjectFiles.forProject(projectRoot);
		files.stage(GENERATED_PROJECT, GENERATED_CLASSPATH);
		files.unstage();

		EclipseProjectFiles.refuseIfDirty(projectRoot); // toujours pas, une fois restauré
	}

	@Test
	@DisplayName("un .project original resté dans .clide/tmp/ (crash entre stage et unstage) est détecté")
	void refuseIfDirtyCatchesAStrandedOriginal(@TempDir final Path projectRoot) throws IOException {
		// Simule le crash le plus défavorable : le déplacement de .project a réussi,
		// celui de .classpath n'a jamais eu lieu - refuseIfDirty doit quand même
		// s'en apercevoir, sur .project seul.
		Files.createDirectories(projectRoot.resolve(".clide/tmp"));
		Files.writeString(projectRoot.resolve(".clide/tmp/.project"), "real, coincé là par un crash",
				StandardCharsets.UTF_8);

		final IOException thrown = assertThrows(IOException.class, () -> EclipseProjectFiles.refuseIfDirty(projectRoot));
		assertTrue(thrown.getMessage().contains(".project"), "le message doit nommer le fichier concerné");
	}

	@Test
	@DisplayName("les copies de debug ne comptent pas comme un état sale")
	void refuseIfDirtyIgnoresDebugCopies(@TempDir final Path projectRoot) throws IOException {
		// Un .project.clide qui traîne d'un run précédent est normal (jamais
		// nettoyé, voir la doc de la classe) - seuls .project/.classpath eux-mêmes,
		// sous .clide/tmp/, signalent un crash.
		Files.createDirectories(projectRoot.resolve(".clide/tmp"));
		Files.writeString(projectRoot.resolve(".clide/tmp/.project.clide"), GENERATED_PROJECT, StandardCharsets.UTF_8);
		Files.writeString(projectRoot.resolve(".clide/tmp/.classpath.clide"), GENERATED_CLASSPATH,
				StandardCharsets.UTF_8);

		EclipseProjectFiles.refuseIfDirty(projectRoot); // ne doit pas lever
	}

}
