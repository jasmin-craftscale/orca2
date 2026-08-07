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
	@DisplayName("records exactly which rule sets are still empty, so nothing passes vacuously unnoticed")
	void whatIsStillEmptyIsStated() {
		// WP4 put the first classes into `execution` and WP7 into `integration`, so
		// the module wall is no longer governing nothing — for those two. The other
		// three are still empty and still carry allowEmptyShould(true), so the
		// statement has to be kept exact rather than deleted wholesale.
		//
		// orca-runtime/AGENTS.md said to delete this test outright when the first
		// class landed. That instruction assumed all five modules would populate at
		// once. Deleting it now would remove the record of three rule sets that really
		// are still empty — which is the one thing this test exists to prevent — so
		// it is narrowed instead, and made to assert BOTH halves: what is populated,
		// and what is not.
		//
		// ⚠️ THIS TEST HAS NOW FIRED FOR REAL, TWICE OVER. WP7 added the connector to
		// `integration` and this failed the build naming the module — which is what a
		// guard is for, and is the difference between a recorded exemption and a
		// forgotten one.
		assertThat(classesIn("execution"))
				.as("execution holds the delegates, the engine gateway and admission. If this is "
						+ "ever zero again, ModuleWallRule and EngineConfinementRule are both passing "
						+ "over nothing and the allowEmptyShould below has become a blanket exemption")
				.isPositive();

		assertThat(classesIn("integration"))
				.as("integration holds WP7's connector, its configuration and the ConnectorPort the "
						+ "execution module's delegate calls — which is the first time the module wall "
						+ "governs a REAL cross-module dependency rather than an empty set")
				.isPositive();

		for (String module : List.of("workitem", "notify", "readmodel")) {
			assertThat(classesIn(module))
					.as("runtime module '%s' now has classes — remove it from this list, and remove "
							+ "allowEmptyShould(true) from ModuleWallRule once every module is populated",
							module)
					.isZero();
		}
	}

	private static long classesIn(String module) {
		return OrcaClasses.production().stream()
				.filter(javaClass -> javaClass.getPackageName()
						.startsWith("com.lynxis.orca.runtime." + module))
				.filter(javaClass -> !javaClass.getSimpleName().equals("package-info"))
				.count();
	}
}
