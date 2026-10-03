package clide.profile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import clide.jdtls.JdtlsLauncher;

/**
 * Enregistre pour de vrai : fabrique un « projet » (un dossier avec un fichier
 * src/main/java/fixture/Burner.java, ne serait-ce que vide - clide ne lit jamais
 * les sources, il regarde seulement si le fichier existe), puis fait tourner
 * fixture.Burner dans un JVM forké avec les options que Recording.nextOptions()
 * fournit, depuis ce dossier, comme ProjectTests le fait.
 *
 * Tester ainsi les options elles-mêmes, et pas seulement leur lecture, est le
 * but : un nom de fichier mal formé ou une option JFR mal orthographiée
 * donneraient un JVM qui tourne sans rien enregistrer, et rien d'autre ne le
 * verrait.
 */
public final class ProfiledRun {

	public final Path root;
	public final Recording recording;

	private ProfiledRun(final Path root, final Recording recording) {
		this.root = root;
		this.recording = recording;
	}

	/** root est un dossier vide, typiquement un @TempDir. */
	public static ProfiledRun of(final Path root) throws IOException, InterruptedException {
		final Path source = root.resolve("src/main/java/fixture/Burner.java");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "package fixture;\n");

		final Recording recording = Recording.start(root);
		final List<String> command = new ArrayList<>();
		command.add(JdtlsLauncher.javaExecutable());
		command.addAll(recording.nextOptions());
		// En absolu : le JVM tourne dans le dossier du projet, et un classpath relatif
		// au dossier de lancement des tests n'y trouverait plus Burner - il sortirait
		// aussitôt, avec un enregistrement vide mais bien formé.
		final List<String> classpath = new ArrayList<>();
		for (final String entry : System.getProperty("java.class.path").split(File.pathSeparator))
			classpath.add(Path.of(entry).toAbsolutePath().toString());

		command.addAll(List.of("-cp", String.join(File.pathSeparator, classpath), "fixture.Burner"));

		final Path output = root.resolve("burner.out");
		final Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
				.redirectOutput(output.toFile()).start();
		if (process.waitFor(120, TimeUnit.SECONDS) == false) {
			process.destroyForcibly();
			throw new IllegalStateException("le JVM cobaye n'a pas rendu la main");
		}

		if (process.exitValue() != 0)
			throw new IllegalStateException("le JVM cobaye a échoué : " + Files.readString(output));

		return new ProfiledRun(root, recording);
	}

	public ProfileScope scope() {
		return ProfileScope.of(root, false);
	}

}
