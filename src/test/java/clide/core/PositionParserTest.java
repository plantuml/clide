package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PositionParser.normalizeSeparators() - the one line responsible for a
 * &lt;position&gt; notation's &lt;file path&gt; field always reading
 * forward-slash-separated, even out of a Windows daemon whose own
 * Path.relativize(...).toString() would otherwise answer with backslashes.
 *
 * Tested directly on strings, deliberately not through a real
 * java.nio.file.Path: on Linux, where this suite runs, no Path ever produces
 * a backslash-separated string to begin with, so exercising the fix through
 * relativize() here would only prove the untouched, already-correct half of
 * the platforms this exists for. See SymbolNotationTest for the
 * end-to-end path this feeds into.
 */
class PositionParserTest {

	@Test
	@DisplayName("un chemin Windows (séparateurs '\\') devient un chemin de notation ('/'))")
	void backslashesBecomeForwardSlashes() {
		assertEquals("src/main/java/pkg/Foo.java",
				PositionParser.normalizeSeparators("src\\main\\java\\pkg\\Foo.java"));
	}

	@Test
	@DisplayName("un chemin déjà en '/' ne change pas")
	void alreadyForwardSlashIsUnchanged() {
		assertEquals("src/main/java/pkg/Foo.java",
				PositionParser.normalizeSeparators("src/main/java/pkg/Foo.java"));
	}

	@Test
	@DisplayName("un nom de fichier seul, sans séparateur, ne change pas")
	void aBareFilenameIsUnchanged() {
		assertEquals("Foo.java", PositionParser.normalizeSeparators("Foo.java"));
	}

}
