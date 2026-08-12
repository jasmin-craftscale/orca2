package com.lynxis.orca.checks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The check on the checks.
 *
 * <p>Most rules in this module are "no class should ..." rules. A rule that sees
 * no classes passes, silently and permanently. This guard asserts the importer is
 * actually seeing the modules and files the other checks govern.
 */
class ImportedSetGuard {

	@Test
	@DisplayName("the importer actually saw classes - a rule over an empty set proves nothing")
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

		assertThat(annotatedRestControllers())
				.as("ContractInterfaceRule governs every @RestController. If this ever drops to the "
						+ "six health controllers, either a controller was deleted or the annotation "
						+ "is no longer visible to the importer")
				.isGreaterThanOrEqualTo(8);
	}

	@Test
	@DisplayName("the two file-reading rules found the repository, not an empty directory")
	void theFileReadingRulesSeeTheRepository() {
		assertThat(RepositoryFiles.serviceContracts())
				.as("six services, six authored contracts. InternalSurfaceRule reads these")
				.hasSizeGreaterThanOrEqualTo(6);

		assertThat(RepositoryFiles.migrations())
				.as("ScopeIndexRule reads the services' migrations and the primitives'")
				.hasSizeGreaterThanOrEqualTo(15);
	}

	private static long annotatedRestControllers() {
		return OrcaClasses.production().stream()
				.filter(javaClass -> javaClass.isAnnotatedWith(
						"org.springframework.web.bind.annotation.RestController"))
				.count();
	}

	@Test
	@DisplayName("all runtime module rule sets are populated")
	void runtimeModuleRuleSetsArePopulated() {
		// This guard has now fired for every runtime module that used to be empty.
		// That evidence matters: it proves the module wall and its import set are
		// wired before the final empty-rule exemption is removed.
		assertThat(classesIn("execution"))
				.as("execution holds the delegates, the engine gateway and admission")
				.isPositive();

		assertThat(classesIn("integration"))
				.as("integration holds the connector configuration and ConnectorPort seam")
				.isPositive();

		assertThat(classesIn("workitem"))
				.as("workitem holds the implemented lifecycle and its API seams")
				.isPositive();

		assertThat(classesIn("notify"))
				.as("notify has now been entered by Stream 2; later work packages add delivery")
				.isPositive();

		assertThat(classesIn("readmodel"))
				.as("readmodel owns the lane-monitor projection and its API port")
				.isPositive();
	}

	private static long classesIn(String module) {
		return OrcaClasses.production().stream()
				.filter(javaClass -> javaClass.getPackageName()
						.startsWith("com.lynxis.orca.runtime." + module))
				.filter(javaClass -> !javaClass.getSimpleName().equals("package-info"))
				.count();
	}
}
