package com.lynxis.orca.checks;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.lynxis.orca.platform.web.internal.InternalCallProperties;

/**
 * Keeps every internal operation under the path pattern guarded by the
 * service-to-service authentication filter; a mismatch stops the build.
 *
 * <h2>What can go wrong, and why nothing else would catch it</h2>
 *
 * <p>Service-to-service calls carry a per-installation shared credential and mint
 * no token, because token <em>issuing</em> would put the identity provider on the
 * gate path. One filter matches one path pattern — {@code /internal/**} — and the
 * pattern is the <strong>only</strong> thing binding an endpoint to that filter.
 *
 * <p>An operation authored one level up, at {@code /commands/v1} rather than
 * {@code /internal/commands/v1}, is not caught by anything. The document generates
 * cleanly, the controller implements the generated interface, the envelope rule
 * passes — and the endpoint falls through to {@code anyRequest().authenticated()},
 * which requires a <em>user</em> token that no service has or can obtain. The route
 * is then unreachable to its only caller, and the tempting repair is to permit it.
 *
 * <h2>Three properties, and where the pattern comes from</h2>
 *
 * <ol>
 *   <li>Every operation tagged {@code internal…} maps under the filter's prefix.</li>
 *   <li>Every path under that prefix is tagged {@code internal…} — so the generated
 *       interface is an {@code Internal…Api} and the surface is legible from the
 *       document. Without this half, the rule is one-directional and a path could
 *       claim the prefix while presenting itself as public.</li>
 *   <li>Every path in every service contract is under {@code /api/} or the internal
 *       prefix. No third space, where neither gate is the intended one.</li>
 * </ol>
 *
 * <p><strong>The prefix is read from {@link InternalCallProperties} rather than
 * written here.</strong> That is the anti-drift half: change the filter's default
 * pattern and this rule changes with it, so the check can never be asserting a
 * pattern the filter no longer matches — which is exactly the silent-miss this rule
 * is named for.
 *
 * <p>It reads the <strong>authored</strong> OpenAPI documents, which are the source of
 * truth, not the compiled controllers. A route that exists only in Java is
 * {@link ContractInterfaceRule}'s business, and the two together are what make the
 * served document a complete description of the surface.
 */
class InternalSurfaceRule {

	private static final List<String> HTTP_METHODS =
			List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

	/** The public surface. Every service's health route is here and nothing else yet. */
	private static final String PUBLIC_PREFIX = "/api/";

	@Test
	@DisplayName("every operation on an internal contract surface maps under the internal-auth filter's pattern")
	void internalOperationsMapUnderTheInternalPrefix() {
		String prefix = internalPrefix();
		List<Operation> operations = operations();
		List<String> violations = new ArrayList<>();

		for (Operation operation : operations) {
			boolean taggedInternal = operation.tags.stream()
					.anyMatch(tag -> tag.toLowerCase(Locale.ROOT).startsWith("internal"));
			boolean underInternalPrefix = operation.path.startsWith(prefix);

			if (taggedInternal && !underInternalPrefix) {
				violations.add(("%s %s (%s, tags %s) is an INTERNAL operation and does not map under "
						+ "'%s'. The internal-auth filter matches that pattern and nothing else, so this route "
						+ "falls through to anyRequest().authenticated() — which needs a USER token that "
						+ "no service mints or can obtain.")
						.formatted(operation.method.toUpperCase(Locale.ROOT), operation.path,
								operation.document, operation.tags, prefix));
			}
			if (underInternalPrefix && !taggedInternal) {
				violations.add(("%s %s (%s) maps under '%s' but carries no internal tag %s. The tag is "
						+ "what names the surface in the served document and what the generated "
						+ "interface is called; a path that claims the prefix while presenting itself "
						+ "as public is the same defect read the other way round.")
						.formatted(operation.method.toUpperCase(Locale.ROOT), operation.path,
								operation.document, prefix, operation.tags));
			}
			if (!underInternalPrefix && !operation.path.startsWith(PUBLIC_PREFIX)) {
				violations.add(("%s %s (%s) is under neither '%s' nor '%s'. There are two surfaces and "
						+ "two gates; a third space is one where neither is the intended one.")
						.formatted(operation.method.toUpperCase(Locale.ROOT), operation.path,
								operation.document, PUBLIC_PREFIX, prefix));
			}
		}

		assertThat(violations)
				.as("Internal service authentication uses one filter matching one path pattern. The pattern is the "
						+ "only thing binding an endpoint to it")
				.isEmpty();

		// A rule that read no documents passes. Six services, at least one internal
		// operation each on edge and runtime.
		assertThat(operations)
				.as("no operations were read at all — this rule is looking at the wrong files")
				.hasSizeGreaterThanOrEqualTo(6);
		assertThat(operations.stream().filter(operation -> operation.path.startsWith(prefix)).toList())
				.as("no internal operations were found, so the half of this rule that matters most "
						+ "governs nothing")
				.hasSizeGreaterThanOrEqualTo(2);
	}

	@Test
	@DisplayName("the prefix this rule enforces is the filter's own, not a copy of it")
	void thePrefixComesFromTheFilterItself() {
		assertThat(new InternalCallProperties().getPathPattern())
				.as("if the filter's default pattern ever stops being a '/…/**' prefix match, this "
						+ "rule's reading of it is wrong and must be revisited rather than adjusted "
						+ "until it passes")
				.endsWith("/**");
		assertThat(internalPrefix()).isEqualTo("/internal/");
	}

	// ------------------------------------------------------------------------

	/** {@code /internal/**} → {@code /internal/}. Read from the filter's own properties. */
	private static String internalPrefix() {
		String pattern = new InternalCallProperties().getPathPattern();
		return pattern.endsWith("**") ? pattern.substring(0, pattern.length() - 2) : pattern;
	}

	@SuppressWarnings("unchecked")
	private static List<Operation> operations() {
		List<Operation> operations = new ArrayList<>();
		for (Path contract : RepositoryFiles.serviceContracts()) {
			Map<String, Object> document = new Yaml().load(RepositoryFiles.read(contract));
			Object paths = document == null ? null : document.get("paths");
			if (!(paths instanceof Map<?, ?> pathsByRoute)) {
				continue;
			}
			String name = RepositoryFiles.root().relativize(contract).toString();
			pathsByRoute.forEach((route, item) -> {
				if (!(item instanceof Map<?, ?> methods)) {
					return;
				}
				methods.forEach((method, operation) -> {
					if (!HTTP_METHODS.contains(String.valueOf(method).toLowerCase(Locale.ROOT))
							|| !(operation instanceof Map<?, ?> body)) {
						return;
					}
					Object tags = body.get("tags");
					operations.add(new Operation(String.valueOf(route), String.valueOf(method),
							tags instanceof List<?> declared
									? declared.stream().map(String::valueOf).toList()
									: List.of(),
							name));
				});
			});
		}
		return operations;
	}

	private record Operation(String path, String method, List<String> tags, String document) {
	}
}
