package com.lynxis.orca.checks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The check on the checks.
 *
 * <p>Every rule in this module is of the form "no class should…". A rule that sees
 * <em>no classes</em> passes, silently and permanently, and a build check nobody
 * has watched fail may not be wired in at all. One misconfigured import option or
 * one module missing from {@code build-checks/build.gradle.kts} is enough.
 *
 * <p>So this asserts what was actually imported: every module by name, and a
 * plausible floor on the total. It is the reason the other five rules can be
 * believed.
 */
class ImportedSetGuard {

	@Test
	@DisplayName("the importer actually saw classes — a rule over an empty set proves nothing")
	void theImportIsNotEmpty() {
		assertThat(OrcaClasses.production().size())
				.as("no ORCA classes were imported: every rule in this module would pass vacuously")
				.isGreaterThan(40);
	}

	@Test
	@DisplayName("every one of the twelve modules is on the classpath the rules see")
	void everyModuleIsVisible() {
		List<String> mustBePresent = List.of(
				"com.lynxis.orca.platform.outbox.OutboxRelay",
				"com.lynxis.orca.platform.lease.JdbcLeaseManager",
				"com.lynxis.orca.platform.scope.JdbcScopeSeam",
				"com.lynxis.orca.platform.idempotency.JdbcIdempotencyStore",
				"com.lynxis.orca.platform.web.ApiResponse",
				"com.lynxis.orca.core.CoreApplication",
				"com.lynxis.orca.runtime.RuntimeApplication",
				"com.lynxis.orca.edge.EdgeApplication",
				"com.lynxis.orca.portal.PortalApplication",
				"com.lynxis.orca.sync.SyncApplication",
				"com.lynxis.orca.fleet.FleetApplication");

		List<String> imported = OrcaClasses.production().stream()
				.map(javaClass -> javaClass.getFullName())
				.toList();

		assertThat(imported)
				.as("a module missing from build-checks' dependencies is a module whose violations "
						+ "are simply not seen")
				.containsAll(mustBePresent);
	}

	@Test
	@DisplayName("the controllers the envelope rule governs are actually visible to it")
	void theControllersAreVisible() {
		long controllers = OrcaClasses.production().stream()
				.filter(javaClass -> javaClass.getSimpleName().equals("HealthController"))
				.count();

		assertThat(controllers)
				.as("six services, six hand-written controllers implementing six generated interfaces")
				.isEqualTo(6);
	}

	@Test
	@DisplayName("records exactly which rule sets are still empty in Phase 0, so nothing passes vacuously unnoticed")
	void whatIsStillEmptyIsStated() {
		// Phase 0 builds no business logic, so orca-runtime's five modules hold only
		// package declarations and the module-wall rule governs nothing yet. That is
		// expected — and stating it here is what stops it from being mistaken for a
		// rule that has been checked. When the first class lands in a module, this
		// test fails and is deleted.
		for (String module : OrcaClasses.RUNTIME_MODULES) {
			long classes = OrcaClasses.production().stream()
					.filter(javaClass -> javaClass.getPackageName()
							.startsWith("com.lynxis.orca.runtime." + module))
					.filter(javaClass -> !javaClass.getSimpleName().equals("package-info"))
					.count();
			assertThat(classes)
					.as("runtime module '%s' now has classes — the module-wall rule is no longer empty, "
							+ "so remove allowEmptyShould(true) from ModuleWallRule and delete this test",
							module)
					.isZero();
		}
	}
}
