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
 * Tests de MoveClassCommand qui ne demandent aucun jdtls : le refus d'un
 * &lt;new package&gt; inacceptable, la lecture de la déclaration "package"
 * d'un fichier (readDeclaredPackage), et le rendu du compte rendu.
 *
 * Tout ce que ces tests ne couvrent pas volontairement - le décalage entre
 * chemin et package (PACKAGE_DIRECTORY_MISMATCH), le "déjà dans ce package"
 * qui court-circuite, la destination déjà occupée, l'edit de jdtls et le
 * déplacement lui-même - passe par siblingTopLevelTypeNames(), qui a besoin
 * d'une vraie session ; ça ne veut rien dire sans un vrai serveur en face et
 * se vérifie de bout en bout (clide sur clide), pas ici.
 */
class MoveClassCommandTest {

	private static final String SOURCE = "package demo;\n\npublic class Square {\n}\n";

	// ------------------------------------------------------------------
	// Refus d'un <new package>
	// ------------------------------------------------------------------

	@Test
	@DisplayName("un mot réservé Java dans un segment est refusé, sans rien demander à jdtls")
	void aReservedWordSegmentIsRefused(@TempDir final Path root) throws IOException {
		final CommandResult result = moveClass(root, "demo.class.other");

		assertEquals(ErrorCode.INVALID_JAVA_PACKAGE_NAME, result.code());
		assertTrue(result.message().contains("reserved word"), result.message());
	}

	@Test
	@DisplayName("un package vide est refusé")
	void anEmptyPackageIsRefused(@TempDir final Path root) throws IOException {
		assertEquals(ErrorCode.INVALID_JAVA_PACKAGE_NAME, moveClass(root, "   ").code());
	}

	@Test
	@DisplayName("un segment vide (deux points de suite) est refusé")
	void anEmptySegmentIsRefused(@TempDir final Path root) throws IOException {
		final CommandResult result = moveClass(root, "demo..other");

		assertEquals(ErrorCode.INVALID_JAVA_PACKAGE_NAME, result.code());
		assertTrue(result.message().contains("empty segment"), result.message());
	}

	@Test
	@DisplayName("un segment qui commence par un chiffre est refusé")
	void aSegmentStartingWithADigitIsRefused(@TempDir final Path root) throws IOException {
		assertEquals(ErrorCode.INVALID_JAVA_PACKAGE_NAME, moveClass(root, "demo.2bad").code());
	}

	@Test
	@DisplayName("un '$' est refusé bien que Java l'autorise dans un identifiant")
	void aDollarIsRefusedAlthoughJavaAllowsIt(@TempDir final Path root) throws IOException {
		final CommandResult result = moveClass(root, "demo.sub$pkg");

		assertEquals(ErrorCode.INVALID_JAVA_PACKAGE_NAME, result.code());
		assertTrue(result.message().contains("$"), result.message());
	}

	@Test
	@DisplayName("un nom de package ordinaire passe la validation - l'échec qui suit vient de la session absente")
	void anOrdinaryPackageNamePassesValidation(@TempDir final Path root) throws IOException {
		try {
			moveClass(root, "demo.other");
			throw new AssertionError("expected a NullPointerException from the null session, got none");
		} catch (final NullPointerException expected) {
			// the package name is accepted, so executeCommand went on to ask the
			// (null, in this test) session for the file's sibling top-level types -
			// proof the package validation itself passed
		}
	}

	// ------------------------------------------------------------------
	// readDeclaredPackage() - la seule logique testable sans jdtls
	// ------------------------------------------------------------------

	@Test
	@DisplayName("lit le package déclaré d'un fichier")
	void readsTheDeclaredPackage(@TempDir final Path root) throws IOException {
		final Path file = root.resolve("Square.java");
		Files.writeString(file, "package demo.shapes;\n\npublic class Square {\n}\n", StandardCharsets.UTF_8);

		assertEquals("demo.shapes", MoveClassCommand.readDeclaredPackage(file));
	}

	@Test
	@DisplayName("un fichier sans déclaration \"package\" est dans le package par défaut")
	void aFileWithNoPackageDeclarationIsInTheDefaultPackage(@TempDir final Path root) throws IOException {
		final Path file = root.resolve("Square.java");
		Files.writeString(file, "public class Square {\n}\n", StandardCharsets.UTF_8);

		assertEquals("", MoveClassCommand.readDeclaredPackage(file));
	}

	@Test
	@DisplayName("la déclaration \"package\" est reconnue même entourée d'espaces")
	void thePackageDeclarationIsRecognisedWithExtraWhitespace(@TempDir final Path root) throws IOException {
		final Path file = root.resolve("Square.java");
		Files.writeString(file, "  package   demo.shapes  ;  \n\npublic class Square {\n}\n", StandardCharsets.UTF_8);

		assertEquals("demo.shapes", MoveClassCommand.readDeclaredPackage(file));
	}

	// ------------------------------------------------------------------
	// Rendu
	// ------------------------------------------------------------------

	@Test
	@DisplayName("le compte rendu donne les fichiers, le déplacement à part, la position fraîche et le build")
	void theReportNamesFilesMoveDeclarationAndBuild() {
		final Position position = new Position("f21e4159", "src/demo/shapes/Square.java", 3, 14, "Square");
		final CommandPayload payload = new CommandPayload.MoveClass("Square", "demo", "demo.shapes",
				"src/demo/Square.java", "src/demo/shapes/Square.java",
				Listing.of(List.of("src/demo/Main.java", "src/demo/shapes/Square.java"), 100),
				new CodeLocation(position, "public class Square {"), 0);

		final String rendered = new MoveClassCommand().render(CommandResult.ok(payload), PrintMode.AI);

		assertEquals("""
				move_class: Square demo -> demo.shapes, 2 file(s)
				src/demo/Main.java
				src/demo/shapes/Square.java
				file moved: src/demo/Square.java -> src/demo/shapes/Square.java
				declaration now at f21e4159:src/demo/shapes/Square.java:3:14:Square public class Square {
				rebuilt: 0 error(s)""", rendered);
	}

	@Test
	@DisplayName("sans position fraîche, cette ligne disparaît au lieu d'être vide")
	void absentDeclarationSimplyDoesNotPrint() {
		final CommandPayload payload = new CommandPayload.MoveClass("Square", "demo", "demo.shapes",
				"src/demo/Square.java", "src/demo/shapes/Square.java",
				Listing.of(List.of("src/demo/shapes/Square.java"), 100), null, 2);

		assertEquals("""
				move_class: Square demo -> demo.shapes, 1 file(s)
				src/demo/shapes/Square.java
				file moved: src/demo/Square.java -> src/demo/shapes/Square.java
				rebuilt: 2 error(s)""", new MoveClassCommand().render(CommandResult.ok(payload), PrintMode.AI));
	}

	@Test
	@DisplayName("un move_class qui ne change rien le dit, plutôt que d'annoncer un déplacement")
	void aMoveThatChangesNothingSaysSo() {
		final Position position = new Position("f21e4159", "src/demo/Square.java", 3, 14, "Square");
		final CommandPayload payload = new CommandPayload.MoveClass("Square", "demo", "demo",
				"src/demo/Square.java", "src/demo/Square.java", Listing.of(List.of(), 100),
				new CodeLocation(position, "public class Square {"), 0);

		assertEquals("move_class: nothing to change - 'Square' is already in package 'demo'",
				new MoveClassCommand().render(CommandResult.ok(payload), PrintMode.AI));
	}

	// ------------------------------------------------------------------
	// Outillage
	// ------------------------------------------------------------------

	private static CommandResult moveClass(final Path root, final String newPackage) throws IOException {
		final Path file = root.resolve("Square.java");
		Files.createDirectories(root);
		Files.writeString(file, SOURCE, StandardCharsets.UTF_8);

		final FilesRepository files_ = new FilesRepository(root, new Md5Repository(root));
		final ClideContext context = new ClideContext(files_, null, List.of());
		return new MoveClassCommand().executeCommand(context, "Square.java:3:14:Square", newPackage);
	}

}
