package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.command.answer.ErrorCode;
import clide.model.Position;

/**
 * Tests de PositionParser.parse() sur la notation canonique
 * chemin:ligne:colonne:nom : le code d'erreur porté par chaque refus, et le
 * contrôle de cohérence qui exige que le nom commence bien à la colonne
 * annoncée.
 *
 * Ce contrôle est ce qui remplace l'ancienne résolution implicite : sans
 * colonne, "a.calculer(b.calculer())" désignait deux méthodes sans rapport et
 * clide répondait silencieusement sur la première, avec un simple
 * avertissement. Désormais chaque colonne désigne l'une ou l'autre, et une
 * colonne fausse est refusée plutôt qu'arrondie.
 */
class PositionCodesTest {

	private static Path write(final Path dir, final String name, final String... lines) throws IOException {
		final Path file = dir.resolve(name);
		Files.write(file, List.of(lines));
		return file;
	}

	private static ErrorCode codeOf(final Path root, final String token) {
		final FilesRepository filesRepository = new FilesRepository(root, null);
		final PositionException thrown = assertThrows(PositionException.class, () -> PositionParser.parse(filesRepository, token));
		return thrown.getCode();
	}

	@Test
	@DisplayName("une notation qui ne ressemble pas à chemin:ligne:colonne:nom est MALFORMED_POSITION")
	void malformedNotation(@TempDir final Path root) {
		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "Foo.java"));
		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "Foo.java:douze:1:bar"));
		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "Foo.java:12:deux:bar"));
	}

	@Test
	@DisplayName("l'ancienne notation sans colonne n'est plus acceptée")
	void threePartNotationIsRejected(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "}");

		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "Foo.java:1:Foo"));
	}

	@Test
	@DisplayName("un fichier absent est FILE_NOT_FOUND, pas une notation malformée")
	void missingFile(@TempDir final Path root) {
		assertEquals(ErrorCode.FILE_NOT_FOUND, codeOf(root, "Absent.java:1:1:bar"));
	}

	@Test
	@DisplayName("une URI file: qui pointe hors du projet est FILE_NOT_FOUND, pas acceptée comme un chemin y échappant en ../")
	void fileUriOutsideProjectIsRejected(@TempDir final Path root, @TempDir final Path elsewhere) throws IOException {
		final Path outside = write(elsewhere, "Outside.java", "class Outside {", "}");

		assertEquals(ErrorCode.FILE_NOT_FOUND, codeOf(root, outside.toUri() + ":1:1:Outside"));
	}

	@Test
	@DisplayName("une ligne hors du fichier est LINE_OUT_OF_RANGE et le message dit combien il y en a")
	void lineOutOfRange(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		final PositionException thrown = assertThrows(PositionException.class,
				() -> PositionParser.parse(filesRepository, "Foo.java:99:7:Foo"));

		assertEquals(ErrorCode.LINE_OUT_OF_RANGE, thrown.getCode());
		assertTrue(thrown.getMessage().contains("file has 2 line(s)"));
	}

	@Test
	@DisplayName("un nom absent de la ligne est NAME_NOT_ON_LINE, quelle que soit la colonne")
	void nameNotOnLine(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "}");

		assertEquals(ErrorCode.NAME_NOT_ON_LINE, codeOf(root, "Foo.java:1:1:absent"));
	}

	@Test
	@DisplayName("une colonne exacte est acceptée et conservée telle quelle, 1-based")
	void exactColumnIsAccepted(@TempDir final Path root) throws IOException {
		// "\tvoid calculer() {" : la tabulation occupe la colonne 1, "void " les
		// colonnes 2 à 6, donc "calculer" commence en colonne 7.
		write(root, "Foo.java", "class Foo {", "\tvoid calculer() {", "\t}", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		final Position position = PositionParser.parse(filesRepository, "Foo.java:2:7:calculer");

		assertEquals(2, position.line());
		assertEquals(7, position.column());
		assertEquals("calculer", position.name());
	}

	@Test
	@DisplayName("le nom présent ailleurs sur la ligne est NAME_NOT_AT_COLUMN, et le hint donne les vraies colonnes")
	void nameElsewhereOnTheLine(@TempDir final Path root) throws IOException {
		// "\t\ta.calculer(b.calculer());" - deux appels sans rapport sur une ligne.
		// Colonnes 1-based comptées à la main : les deux tabulations occupent 1 et 2,
		// "a." 3-4, donc le premier "calculer" commence en 5 ; "(b." occupe 13-15,
		// donc le second commence en 16.
		write(root, "Foo.java", "class Foo {", "\t\ta.calculer(b.calculer());", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		final PositionException thrown = assertThrows(PositionException.class,
				() -> PositionParser.parse(filesRepository, "Foo.java:2:9:calculer"));

		assertEquals(ErrorCode.NAME_NOT_AT_COLUMN, thrown.getCode());
		assertTrue(thrown.getHint().contains("5"));
		assertTrue(thrown.getHint().contains("16"));
	}

	@Test
	@DisplayName("chaque occurrence d'une ligne ambiguë est atteignable par sa propre colonne")
	void eachOccurrenceHasItsOwnColumn(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "\t\ta.calculer(b.calculer());", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		assertEquals(5, PositionParser.parse(filesRepository, "Foo.java:2:5:calculer").column());
		assertEquals(16, PositionParser.parse(filesRepository, "Foo.java:2:16:calculer").column());
	}

	@Test
	@DisplayName("la correspondance reste sur le mot entier - calculerTout n'est pas calculer")
	void wholeWordOnly(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "\t\tcalculerTout();", "}");

		assertEquals(ErrorCode.NAME_NOT_ON_LINE, codeOf(root, "Foo.java:2:3:calculer"));
	}

	@Test
	@DisplayName("toString() rend la notation canonique complète, md5 et colonne compris - le chemin relatif au projet")
	void toStringIsTheCanonicalNotation(@TempDir final Path root) throws IOException {
		final Path file = write(root, "Foo.java", "class Foo {", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		// La forme courte entre, la forme longue sort : c'est toute l'asymétrie de
		// la notation, et ce qui fait qu'un résultat recopié tel quel porte la
		// signature même quand le token qui l'a produit ne la portait pas.
		assertEquals(Position.abbreviate(Md5Repository.md5Of(file)) + ":Foo.java:1:7:Foo",
				PositionParser.parse(filesRepository, "Foo.java:1:7:Foo").toString());
	}

	// ------------------------------------------------------------------
	// <file-content-md5> : le contrôle de cohérence avec le fichier réel
	// ------------------------------------------------------------------

	@Test
	@DisplayName("un md5 qui correspond au contenu actuel est accepté")
	void matchingMd5IsAccepted(@TempDir final Path root) throws IOException {
		final Path file = write(root, "Foo.java", "class Foo {", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);
		final String md5 = Position.abbreviate(Md5Repository.md5Of(file));

		final Position position = PositionParser.parse(filesRepository, md5 + ":Foo.java:1:7:Foo");

		assertEquals(md5, position.md5());
		assertEquals("Foo.java", position.path());
		assertEquals(7, position.column());
	}

	@Test
	@DisplayName("un md5 périmé est FILE_MODIFIED, même si ligne, colonne et nom sont encore justes")
	void staleMd5IsRejected(@TempDir final Path root) throws IOException {
		final Path file = write(root, "Foo.java", "class Foo {", "}");
		final String before = Position.abbreviate(Md5Repository.md5Of(file));

		// Le commentaire ajouté en tête décale tout d'une ligne, mais on interroge
		// la ligne où "Foo" se trouve *maintenant* : sans le md5, ce token passerait
		// tous les contrôles existants et répondrait sur un fichier qui a changé.
		write(root, "Foo.java", "// ajouté", "class Foo {", "}");

		assertEquals(ErrorCode.FILE_MODIFIED, codeOf(root, before + ":Foo.java:2:7:Foo"));
	}

	@Test
	@DisplayName("FILE_MODIFIED ne donne pas le md5 courant - ce serait livrer le contournement avec l'erreur")
	void staleMd5GivesNoWayToPatchTheToken(@TempDir final Path root) throws IOException {
		final Path file = write(root, "Foo.java", "class Foo {", "}");
		final String before = Position.abbreviate(Md5Repository.md5Of(file));
		write(root, "Foo.java", "// ajouté", "class Foo {", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		// Rien n'a été classé dans Md5Repository (pas de register()) : le hint n'a
		// aucune preuve à exploiter, donc aucune chance de faire fuiter le md5
		// courant par cette voie non plus.
		final PositionException thrown = assertThrows(PositionException.class,
				() -> PositionParser.parse(filesRepository, before + ":Foo.java:2:7:Foo"));

		assertEquals("", thrown.getHint());
		assertFalse(thrown.getMessage().contains(Position.abbreviate(Md5Repository.md5Of(file))));
	}

	@Test
	@DisplayName("le md5 est vérifié avant la ligne et le nom - la cause, pas le symptôme")
	void md5IsCheckedBeforeLineAndName(@TempDir final Path root) throws IOException {
		final Path file = write(root, "Foo.java", "class Foo {", "}");
		final String before = Position.abbreviate(Md5Repository.md5Of(file));
		write(root, "Foo.java", "class Bar {", "}");

		// Sur le fichier tel qu'il est, la ligne 99 n'existe pas et "Foo" n'est plus
		// nulle part : deux refus possibles, et c'est bien le md5 qui doit parler.
		assertEquals(ErrorCode.FILE_MODIFIED, codeOf(root, before + ":Foo.java:99:7:Foo"));
		assertEquals(ErrorCode.FILE_MODIFIED, codeOf(root, before + ":Foo.java:1:7:Foo"));
	}

	@Test
	@DisplayName("sans md5, la forme courte reste acceptée - implicitement « sur le fichier actuel »")
	void shortFormIsStillAccepted(@TempDir final Path root) throws IOException {
		final Path file = write(root, "Foo.java", "class Foo {", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		final Position position = PositionParser.parse(filesRepository, "Foo.java:1:7:Foo");

		assertEquals(Position.abbreviate(Md5Repository.md5Of(file)), position.md5());
	}

	@Test
	@DisplayName("un md5 en majuscules est refusé, et l'erreur parle du md5 - pas du chemin")
	void uppercaseMd5IsRejected(@TempDir final Path root) throws IOException {
		final Path file = write(root, "Foo.java", "class Foo {", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);
		final String upper = Position.abbreviate(Md5Repository.md5Of(file)).toUpperCase(Locale.ROOT);

		final PositionException thrown = assertThrows(PositionException.class,
				() -> PositionParser.parse(filesRepository, upper + ":Foo.java:1:7:Foo"));

		assertEquals(ErrorCode.MALFORMED_POSITION, thrown.getCode());
		assertTrue(thrown.getMessage().contains("lowercase"));
	}

	@Test
	@DisplayName("un md5 de longueur invalide retombe sur le chemin - et rend le même code sur toute plateforme")
	void nearlyAnMd5IsReadAsAPath(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "}");

		// "abc123" ne fait pas 32 caractères, donc rien ne le distingue plus d'un
		// début de chemin : le token est lu comme désignant le fichier
		// "abc123:Foo.java". Il n'existe pas sous Unix ; sous Windows il n'est même
		// pas un nom de fichier légal, et resolve() y lève InvalidPathException.
		// Les deux doivent dire FILE_NOT_FOUND - voir resolvePath().
		assertEquals(ErrorCode.FILE_NOT_FOUND, codeOf(root, "abc123:Foo.java:1:7:Foo"));
	}

	@Test
	@DisplayName("un chemin que la plateforme refuse de parser est FILE_NOT_FOUND, pas une InvalidPathException nue")
	void unparsablePathIsRefusedWithACode(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "}");

		// Le caractère nul est illégal dans un chemin sur toute plateforme, là où le
		// ':' de nearlyAnMd5IsReadAsAPath ne l'est que sous Windows : c'est ce qui
		// permet d'exercer la même branche de resolvePath() partout, y compris là où
		// le cas qui l'a fait découvrir ne se reproduit pas.
		final String nul = String.valueOf((char) 0);

		assertEquals(ErrorCode.FILE_NOT_FOUND, codeOf(root, "Fo" + nul + "o.java:1:7:Foo"));
	}

	@Test
	@DisplayName("une URI file: illisible est MALFORMED_POSITION, pas une IllegalArgumentException nue")
	void unparsableFileUriIsRefusedWithACode(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "}");

		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "file://[::::]/Foo.java:1:7:Foo"));
	}

	@Test
	@DisplayName("of() refuse un md5 mal formé de front, plutôt que de le laisser passer pour un bout de chemin")
	void ofRejectsAMalformedMd5(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		final PositionException thrown = assertThrows(PositionException.class,
				() -> PositionParser.of(filesRepository, "pas-un-md5", "Foo.java", 1, 7, "Foo"));

		assertEquals(ErrorCode.MALFORMED_POSITION, thrown.getCode());
	}

	@Test
	@DisplayName("of() avec un md5 null vaut « sur le fichier actuel », comme la forme courte")
	void ofAcceptsANullMd5(@TempDir final Path root) throws IOException {
		final Path file = write(root, "Foo.java", "class Foo {", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		final Position position = PositionParser.of(filesRepository, null, "Foo.java", 1, 7, "Foo");

		assertEquals(Position.abbreviate(Md5Repository.md5Of(file)), position.md5());
	}

	@Test
	@DisplayName("of() avec un md5 périmé refuse, comme le token qui l'épellerait")
	void ofRejectsAStaleMd5(@TempDir final Path root) throws IOException {
		final Path file = write(root, "Foo.java", "class Foo {", "}");
		final String before = Position.abbreviate(Md5Repository.md5Of(file));
		write(root, "Foo.java", "// ajouté", "class Foo {", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		final PositionException thrown = assertThrows(PositionException.class,
				() -> PositionParser.of(filesRepository, before, "Foo.java", 2, 7, "Foo"));

		assertEquals(ErrorCode.FILE_MODIFIED, thrown.getCode());
	}

	@Test
	@DisplayName("PositionException reste une IllegalArgumentException pour les appelants d'avant les codes")
	void stillAnIllegalArgumentException(@TempDir final Path root) {
		final FilesRepository filesRepository = new FilesRepository(root, null);

		assertThrows(IllegalArgumentException.class, () -> PositionParser.parse(filesRepository, "Absent.java:1:1:bar"));
	}

	// ------------------------------------------------------------------
	// FILE_MODIFIED hint : le rattrapage via Md5Repository, jamais un pari
	// ------------------------------------------------------------------

	@Test
	@DisplayName("FILE_MODIFIED donne un hint quand la ligne visée existe intacte ailleurs dans le fichier")
	void hintFindsAnUnmovedLine(@TempDir final Path root) throws IOException {
		final Path file = write(root, "Foo.java", "class Foo {", "\tvoid calculer() {", "\t}", "}");
		// register() est ce qu'un rebuild fait à chaque fichier : c'est ce qui classe
		// le contenu d'origine dans Md5Repository et rend le hint possible plus bas.
		final String before = Position.abbreviate(new Md5Repository(root).register(file));

		// Une ligne insérée en tête décale "calculer" de la ligne 2 à la ligne 3, sans
		// toucher au texte de la ligne elle-même.
		write(root, "Foo.java", "// ajouté", "class Foo {", "\tvoid calculer() {", "\t}", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		final PositionException thrown = assertThrows(PositionException.class,
				() -> PositionParser.parse(filesRepository, before + ":Foo.java:2:7:calculer"));

		assertEquals(ErrorCode.FILE_MODIFIED, thrown.getCode());
		assertTrue(thrown.getHint().contains(":Foo.java:3:7:calculer"));

		// et le hint pointe une position fraîche, elle-même acceptée sans détour
		final String hintPosition = thrown.getHint().substring(thrown.getHint().indexOf("now at ") + "now at ".length());
		final Position resolved = PositionParser.parse(filesRepository, hintPosition);
		assertEquals(3, resolved.line());
		assertEquals(7, resolved.column());
	}

	@Test
	@DisplayName("pas de hint si la ligne visée a elle-même changé - le texte exact ne se retrouve nulle part")
	void noHintWhenTheLineItselfChanged(@TempDir final Path root) throws IOException {
		final Path file = write(root, "Foo.java", "class Foo {", "\tvoid calculer() {", "\t}", "}");
		final String before = Position.abbreviate(new Md5Repository(root).register(file));

		write(root, "Foo.java", "class Foo {", "\tvoid calculerTout() {", "\t}", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		final PositionException thrown = assertThrows(PositionException.class,
				() -> PositionParser.parse(filesRepository, before + ":Foo.java:2:7:calculer"));

		assertEquals(ErrorCode.FILE_MODIFIED, thrown.getCode());
		assertEquals("", thrown.getHint());
	}

	@Test
	@DisplayName("pas de hint si la ligne visée apparaît deux fois dans le nouveau fichier - ambigu")
	void noHintWhenTheLineAppearsTwice(@TempDir final Path root) throws IOException {
		final Path file = write(root, "Foo.java", "class Foo {", "\tvoid calculer() {", "\t}", "}");
		final String before = Position.abbreviate(new Md5Repository(root).register(file));

		write(root, "Foo.java", "class Foo {", "\tvoid calculer() {", "\t}", "\tvoid calculer() {", "\t}", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		final PositionException thrown = assertThrows(PositionException.class,
				() -> PositionParser.parse(filesRepository, before + ":Foo.java:2:7:calculer"));

		assertEquals(ErrorCode.FILE_MODIFIED, thrown.getCode());
		assertEquals("", thrown.getHint());
	}

	@Test
	@DisplayName("pas de hint si le nom du token n'est pas sur la ligne retrouvée")
	void noHintWhenNameIsNotOnTheRecoveredLine(@TempDir final Path root) throws IOException {
		final Path file = write(root, "Foo.java", "class Foo {", "\tvoid calculer() {", "\t}", "}");
		final String before = Position.abbreviate(new Md5Repository(root).register(file));

		write(root, "Foo.java", "// ajouté", "class Foo {", "\tvoid calculer() {", "\t}", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		final PositionException thrown = assertThrows(PositionException.class,
				() -> PositionParser.parse(filesRepository, before + ":Foo.java:2:7:absent"));

		assertEquals(ErrorCode.FILE_MODIFIED, thrown.getCode());
		assertEquals("", thrown.getHint());
	}

}
