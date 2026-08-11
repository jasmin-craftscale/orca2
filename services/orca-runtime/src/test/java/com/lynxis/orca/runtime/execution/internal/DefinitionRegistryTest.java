package com.lynxis.orca.runtime.execution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

import com.lynxis.orca.runtime.execution.engine.UnknownDefinitionException;

/**
 * The registry's one non-obvious property: the workflow id is DERIVED from the
 * definition key ({@code proc_<workflowId>}), so what is deployed IS the
 * uuid-to-id mapping — and anything that does not carry an id in that shape
 * answers 0 rather than something invented.
 */
class DefinitionRegistryTest {

	private final DefinitionRegistry registry = new DefinitionRegistry();

	@Test
	void theWorkflowIdIsDerivedFromTheDefinitionKey() {
		registry.register("uuid-a", "proc_950001");
		assertThat(registry.workflowIdFor("uuid-a")).isEqualTo(950001);
		assertThat(registry.definitionKeyFor("uuid-a")).isEqualTo("proc_950001");
	}

	@Test
	void whatCarriesNoIdAnswersZeroNotAnInvention() {
		registry.register("hand-written", "gateVisit");
		registry.register("mangled", "proc_not-a-number");
		assertThat(registry.workflowIdFor("hand-written")).isZero();
		assertThat(registry.workflowIdFor("mangled")).isZero();
		assertThat(registry.workflowIdFor("never-registered")).isZero();
	}

	@Test
	void theCrossWorkflowMenuListsOnlyRealIds() {
		registry.register("uuid-a", "proc_950001");
		registry.register("hand-written", "gateVisit");
		assertThat(registry.deployedWorkflows())
				.containsExactlyEntriesOf(java.util.Map.of("uuid-a", 950001L));
	}

	@Test
	void anUndeployedUuidIsATypedFailureNotANull() {
		assertThatExceptionOfType(UnknownDefinitionException.class)
				.isThrownBy(() -> registry.definitionKeyFor("nothing-deployed"))
				.withMessageContaining("nothing-deployed");
	}
}
