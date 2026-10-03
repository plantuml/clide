package clide.profile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import clide.jdtls.EclipseDescriptorBuilder;

/**
 * What counts as the project's own code in a recording: a frame is "in scope"
 * when its class has a .java file under one of roots.
 *
 * By default the roots are the production source folders only. A suite of
 * regression tests is not a benchmark, and the harness around the code under
 * test (a test's own XML normalisation, say) can eat a third of the CPU: left in
 * scope it would be the first hotspot every time and hide the code the profile
 * is meant to be about. includeTests puts the test sources back.
 *
 * The folders are the ones clide itself gives jdtls - see
 * EclipseDescriptorBuilder - so main and test are told apart by the same rule
 * that marks them in the generated .classpath.
 */
public record ProfileScope(Path projectRoot, List<Path> roots) {

	public ProfileScope {
		roots = List.copyOf(roots);
	}

	public static ProfileScope of(final Path projectRoot, final boolean includeTests) {
		final List<Path> roots = new ArrayList<>();
		for (final String folder : EclipseDescriptorBuilder.forProject(projectRoot).detectSourceFolders())
			if (includeTests || EclipseDescriptorBuilder.isTestFolder(folder) == false)
				roots.add(projectRoot.resolve(folder));

		return new ProfileScope(projectRoot, roots);
	}

	/**
	 * The project-relative path of the source file of a class, "/"-separated, or
	 * null when the class is not project code. Nested classes resolve to their
	 * outermost class' file. Hidden classes (lambdas, method handles) are never in
	 * scope: their names would otherwise match the file of the class that defines
	 * them by prefix.
	 */
	String sourceOf(final String className) {
		if (className.contains("$$Lambda") || className.contains("/0x"))
			return null;

		final int dollar = className.indexOf('$');
		final String outer = dollar >= 0 ? className.substring(0, dollar) : className;
		final String relative = outer.replace('.', '/') + ".java";
		for (final Path root : roots) {
			final Path file = root.resolve(relative);
			if (Files.isRegularFile(file))
				return projectRoot.relativize(file).toString().replace('\\', '/');
		}
		return null;
	}

}
