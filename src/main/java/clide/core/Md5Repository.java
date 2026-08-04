package clide.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Md5Repository {

	private final Path projectRoot;

	public Md5Repository(Path projectRoot) {
		this.projectRoot = projectRoot;
	}

	public static Md5Repository none() {
		return new Md5Repository(null);
	}

	public Path getProjectRoot() {
		return projectRoot;
	}
	
	// The whole of what a snapshot compares. Costs roughly 3x a plain mtime
	// scan, cache-warm, on a ~3600 .java file project (measured on the PlantUML
	// checkout) - paid once per snapshot, against a jdtls rebuild avoided, and
	// against changes the mtime alone would have missed.
	public static String md5Of(final Path path) throws IOException {
		try {
			final MessageDigest digest = MessageDigest.getInstance("MD5");
			final byte[] hash = digest.digest(Files.readAllBytes(path));
			final StringBuilder hex = new StringBuilder(hash.length * 2);
			for (final byte b : hash)
				hex.append(String.format("%02x", b));
			return hex.toString();
		} catch (final NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	public String register(Path path) throws IOException {
		final String md5 = md5Of(path);
		// En plus de calculer la signature, par exemple d41d8cd98f00b204e9800998ecf8427e
		// On va stocker dans .clide/tmp/md5/d4/d41d8cd98f00b204e9800998ecf8427e.gz
		// le contenu de "path", gzippé en mode "ultra fast"
		// Si jamais le fichier .clide/tmp/md5/d4/d41d8cd98f00b204e9800998ecf8427e.gz existe déjà, on ne fait rien
		// Ca servira plus tard pour les transactions.
		// Pour l'instant, on fait des tests de performance
		return md5;
	}


}
