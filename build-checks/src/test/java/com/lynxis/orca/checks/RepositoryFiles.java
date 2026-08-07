package com.lynxis.orca.checks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The repository's own files, for the two rules that cannot be expressed over
 * bytecode.
 *
 * <p>Most rules here are ArchUnit rules, because most of what they govern is
 * visible in compiled classes. Two things are not: <strong>a migration's index
 * definitions</strong> ({@link ScopeIndexRule}) and <strong>the paths an OpenAPI
 * document declares</strong> ({@link InternalSurfaceRule}). Both are load-bearing,
 * both are hand-authored, and neither leaves a trace in a {@code .class} file — so
 * these rules read the source of truth directly rather than a proxy for it.
 *
 * <p><strong>Every rule that reads files asserts a floor on what it found.</strong>
 * The same reasoning as {@link ImportedSetGuard}: a rule that read no files passes,
 * silently and permanently, and is indistinguishable from a rule that is not wired
 * in. A wrong working directory would do it.
 */
final class RepositoryFiles {

	private RepositoryFiles() {
	}

	/**
	 * The repository root, found by walking up for {@code settings.gradle.kts}.
	 *
	 * <p>Not a relative path from the working directory: Gradle sets that to the
	 * project directory, an IDE may not, and a rule that silently reads nothing is
	 * the failure mode this whole module exists to prevent.
	 */
	static Path root() {
		Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		while (candidate != null) {
			if (Files.exists(candidate.resolve("settings.gradle.kts"))) {
				return candidate;
			}
			candidate = candidate.getParent();
		}
		throw new IllegalStateException("Could not find the repository root (no settings.gradle.kts "
				+ "above " + System.getProperty("user.dir") + "). These rules read files, and a rule "
				+ "that reads nothing passes vacuously.");
	}

	/** Every service's authored OpenAPI document. Generated and bundled copies excluded. */
	static List<Path> serviceContracts() {
		return under(root().resolve("services"), path ->
				path.toString().contains("/src/main/resources/openapi/")
						&& path.getFileName().toString().endsWith(".yaml"));
	}

	/** Every Flyway migration a service or a primitive ships. Test-only migrations excluded. */
	static List<Path> migrations() {
		return Stream.concat(
						under(root().resolve("services"), path ->
								path.toString().contains("/src/main/resources/db/")).stream(),
						under(root().resolve("platform"), path ->
								path.toString().contains("/src/main/resources/db/")).stream())
				.filter(path -> path.getFileName().toString().endsWith(".sql"))
				.sorted()
				.toList();
	}

	static String read(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		}
		catch (IOException unreadable) {
			throw new UncheckedIOException(unreadable);
		}
	}

	private static List<Path> under(Path directory, java.util.function.Predicate<Path> matching) {
		try (Stream<Path> walk = Files.walk(directory)) {
			return walk.filter(Files::isRegularFile)
					.filter(path -> !path.toString().contains("/build/"))
					.filter(matching)
					.sorted(Comparator.comparing(Path::toString))
					.toList();
		}
		catch (IOException unreadable) {
			throw new UncheckedIOException(unreadable);
		}
	}
}
