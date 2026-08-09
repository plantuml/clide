package clide.command.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.PrintMode;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.FilesRepository;
import clide.core.Md5Repository;
import clide.model.CodeLocation;
import clide.model.Listing;
import clide.model.Position;

/**
 * Tests de RenameCommand qui ne demandent aucun jdtls : le refus d'un
 * &lt;new name&gt; inacceptable, et le rendu du compte rendu.
 *
 * Ces deux moitiés se testent ici parce qu'elles se décident avant que la
 * session ne soit touchée, ou après qu'elle a répondu. Tout ce qu'il y a entre
 * les deux — le modèle périmé, prepareRename, l'edit lui-même — ne veut rien
 * dire sans un vrai serveur en face et se vérifie de bout en bout, pas ici.
 *
 * Le contexte porte une vraie FilesRepository sur un répertoire temporaire et
 * une session nulle : la validation du nom se prononce avant le premier appel
 * à jdtls, donc une session absente n'est pas un bouchon, c'est la preuve que
 * rien n'a été demandé au serveur.
 */
class RenameCommandTest {

	private static final String SOURCE = "package demo;\n\npublic class Square {\n}\n";

	// ------------------------------------------------------------------
	// Refus d'un <new name>
	// ------------------------------------------------------------------

	@Test
	@DisplayName("un mot réservé Java est refusé, sans rien demander à jdtls")
	void aReservedWordIsRefused(@TempDir final Path root) throws IOException {
		final CommandResult result = rename(root, "class");

		assertEquals(ErrorCode.INVALID_JAVA_NAME, result.code());
		assertTrue(result.message().contains("reserved word"), result.message());
	}

	@Test
	@DisplayName("un nom qui commence par un chiffre est refusé")
	void aNameStartingWithADigitIsRefused(@TempDir final Path root) throws IOException {
		assertEquals(ErrorCode.INVALID_JAVA_NAME, rename(root, "2Bad").code());
	}

	@Test
	@DisplayName("un nom vide est refusé")
	void anEmptyNameIsRefused(@TempDir final Path root) throws IOException {
		assertEquals(ErrorCode.INVALID_JAVA_NAME, rename(root, "   ").code());
	}

	@Test
	@DisplayName("un '$' est refusé bien que Java l'autorise : la notation <position> ne saurait plus le nommer")
	void aDollarIsRefusedAlthoughJavaAllowsIt(@TempDir final Path root) throws IOException {
		final CommandResult result = rename(root, "Carre$Bis");

		assertEquals(ErrorCode.INVALID_JAVA_NAME, result.code());
		assertTrue(result.message().contains("$"), result.message());
	}

	@Test
	@DisplayName("un identifiant ordinaire passe la validation - l'échec qui suit vient de la session absente")
	void anOrdinaryIdentifierPassesValidation(@TempDir final Path root) throws IOException {
		final CommandResult result = rename(root, "Rectangle");

		assertEquals(ErrorCode.JDTLS_REQUEST_FAILED, result.code(),
				"le nom est accepte, et c'est la session nulle qui arrete la suite");
	}

	// ------------------------------------------------------------------
	// Rendu
	// ------------------------------------------------------------------

	@Test
	@DisplayName("le compte rendu donne les fichiers, le renommage de fichier à part, la position fraîche et le build")
	void theReportNamesFilesRenamesPositionAndBuild() {
		final Position position = new Position("f21e4159", "src/demo/Rectangle.java", 3, 14, "Rectangle");
		final CommandPayload payload = new CommandPayload.Rename("Square", "Rectangle",
				Listing.of(List.of("src/demo/Main.java", "src/demo/Rectangle.java"), 100),
				List.of(new CommandPayload.Rename.FileRenaming("src/demo/Square.java", "src/demo/Rectangle.java")),
				new CodeLocation(position, "public class Rectangle {"), 0);

		final String rendered = new RenameCommand().render(CommandResult.ok(payload), PrintMode.AI);

		assertEquals("""
				rename: Square -> Rectangle, 2 file(s)
				src/demo/Main.java
				src/demo/Rectangle.java
				file renamed: src/demo/Square.java -> src/demo/Rectangle.java
				declaration now at f21e4159:src/demo/Rectangle.java:3:14:Rectangle public class Rectangle {
				rebuilt: 0 error(s)""", rendered);
	}

	@Test
	@DisplayName("sans renommage de fichier ni position fraîche, ces lignes disparaissent au lieu d'être vides")
	void absentRenameAndPositionSimplyDoNotPrint() {
		final CommandPayload payload = new CommandPayload.Rename("calculer", "compute",
				Listing.of(List.of("src/demo/Main.java"), 100), List.of(), null, 3);

		assertEquals("""
				rename: calculer -> compute, 1 file(s)
				src/demo/Main.java
				rebuilt: 3 error(s)""", new RenameCommand().render(CommandResult.ok(payload), PrintMode.AI));
	}

	@Test
	@DisplayName("un rename qui ne change rien le dit, plutôt que d'annoncer 0 fichier")
	void arenameThatChangesNothingSaysSo() {
		final CommandPayload payload = new CommandPayload.Rename("Square", "Square", Listing.of(List.of(), 100),
				List.of(), null, 0);

		assertEquals("rename: nothing to change - 'Square' is already called 'Square'",
				new RenameCommand().render(CommandResult.ok(payload), PrintMode.AI));
	}

	// ------------------------------------------------------------------
	// Outillage
	// ------------------------------------------------------------------

	private static CommandResult rename(final Path root, final String newName) throws IOException {
		final Path file = root.resolve("Square.java");
		Files.createDirectories(root);
		Files.writeString(file, SOURCE, StandardCharsets.UTF_8);

		final FilesRepository files_ = new FilesRepository(root, new Md5Repository(root));
		final ClideContext context = new ClideContext(files_, null, List.of());
		return new RenameCommand().executeCommand(context, "Square.java:3:14:Square", newName);
	}

}
