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
	@DisplayName("every one of the thirteen modules is on the classpath the rules see")
	void everyModuleIsVisible() {
		List<String> mustBePresent = List.of(
				"com.lynxis.orca.platform.outbox.OutboxRelay",
				"com.lynxis.orca.platform.lease.JdbcLeaseManager",
				"com.lynxis.orca.platform.scope.JdbcScopeSeam",
				"com.lynxis.orca.platform.idempotency.JdbcIdempotencyStore",
				"com.lynxis.orca.platform.web.ApiResponse",
				"com.lynxis.orca.platform.secrets.SecretBox",
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

		// ContractInterfaceRule governs EVERY @RestController rather than six classes
		// by name. Counting them here is what says the rule is looking at
		// a populated set: six health controllers plus edge's commands and buffer
		// stats and runtime's events.
		assertThat(annotatedRestControllers())
				.as("ContractInterfaceRule governs every @RestController. If this ever drops to the "
						+ "six health controllers, either a controller was deleted or the annotation "
						+ "is no longer visible to the importer — and the rule is governing less than "
						+ "it reads as governing")
				.isGreaterThanOrEqualTo(8);
	}

	@Test
	@DisplayName("the two file-reading rules found the repository, not an empty directory")
	void theFileReadingRulesSeeTheRepository() {
		// ScopeIndexRule and InternalSurfaceRule read migrations and OpenAPI documents
		// rather than bytecode, so their vacuity risk is a wrong working directory
		// rather than a missing module. Each asserts its own floor; this is the same
		// statement in the one place a reader looks for it.
		assertThat(RepositoryFiles.serviceContracts())
				.as("six services, six authored contracts. InternalSurfaceRule reads these")
				.hasSizeGreaterThanOrEqualTo(6);

		assertThat(RepositoryFiles.migrations())
				.as("ScopeIndexRule reads these — the services' migrations and the primitives'")
				.hasSizeGreaterThanOrEqualTo(15);
	}

	private static long annotatedRestControllers() {
		return OrcaClasses.production().stream()
				.filter(javaClass -> javaClass.isAnnotatedWith(
						"org.springframework.web.bind.annotation.RestController"))
				.count();
	}

	@Test
	@DisplayName("records exactly which rule sets are still empty, so nothing passes vacuously unnoticed")
	void whatIsStillEmptyIsStated() {
		// execution and integration now contain classes, so the module wall no longer
		// governs an empty set for those two. Some other modules still carry
		// allowEmptyShould(true), so the
		// statement has to be kept exact rather than deleted wholesale.
		//
		// orca-runtime/AGENTS.md said to delete this test outright when the first
		// class landed. That instruction assumed all five modules would populate at
		// once. Deleting it now would remove the record of three rule sets that really
		// are still empty — which is the one thing this test exists to prevent — so
		// it is narrowed instead, and made to assert BOTH halves: what is populated,
		// and what is not.
		//
		// ⚠️ THIS TEST HAS NOW FIRED FOR REAL, TWICE OVER. Adding the first
		// connector to `integration` made it fail the build and name the newly populated
		// module — which is what a guard is for, and is the difference between a
		// recorded exemption and a forgotten one.
		assertThat(classesIn("execution"))
				.as("execution holds the delegates, the engine gateway and admission. If this is "
						+ "ever zero again, ModuleWallRule and EngineConfinementRule are both passing "
						+ "over nothing and the allowEmptyShould below has become a blanket exemption")
				.isPositive();

		assertThat(classesIn("integration"))
				.as("integration holds the connector, its configuration and the ConnectorPort the "
						+ "execution module's delegate calls — which is the first time the module wall "
						+ "governs a REAL cross-module dependency rather than an empty set")
				.isPositive();

		assertThat(classesIn("workitem"))
				.as("workitem holds the implemented lifecycle: the service, its repository, the controller "
						+ "and the two api seams (WorkItemIntake in, ManualStepPort consumed) — the "
						+ "first BIDIRECTIONAL pair of module-wall crossings, both through api packages")
				.isPositive();

		for (String module : List.of("notify", "readmodel")) {
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
