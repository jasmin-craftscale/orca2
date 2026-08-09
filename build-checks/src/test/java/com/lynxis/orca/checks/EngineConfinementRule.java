package com.lynxis.orca.checks;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Confines Flowable to {@code runtime.execution} and nowhere else.
 *
 * <p>The engine is reached behind an interface so the platform is not written
 * against one engine's API throughout. Replacing Flowable is the reversible half
 * of the workflow design; replacing the process compiler would be harder. That
 * reversibility is worth exactly as much as the number of places
 * {@code org.flowable} appears, and only this build rule keeps that number down.
 *
 * <p>The failure this prevents is ordinary and cumulative. A {@code readmodel}
 * projection that reaches for {@code HistoryService} because the query is easier
 * there. A work-item service that takes a {@code TaskService} because Flowable has
 * tasks too. Each is defensible on its own; together they are a platform written
 * against one engine's API, discovered only when somebody prices replacing it.
 *
 * <p><strong>What this deliberately does not do:</strong> forbid Flowable inside
 * {@code execution}. The delegates implement {@code JavaDelegate} and the gateway
 * calls {@code RuntimeService} — that is the adapter, and an adapter that could
 * not name the thing it adapts would be a strange rule.
 */
class EngineConfinementRule {

	private static final String FLOWABLE = "org.flowable..";
	private static final String EXECUTION = OrcaClasses.ROOT + ".runtime.execution..";

	@Test
	@DisplayName("no class outside runtime.execution depends on Flowable")
	void flowableStaysInsideTheExecutionModule() {
		noClasses()
				.that().resideOutsideOfPackage(EXECUTION)
				.should().dependOnClassesThat().resideInAnyPackage(FLOWABLE)
				.because("Runtime reaches the engine behind an interface so the platform is not written "
						+ "against a specific engine's API throughout. That is worth what the number of "
						+ "places org.flowable appears is worth, and it only stays small if something "
						+ "keeps it small.")
				.check(OrcaClasses.production());
	}

	@Test
	@DisplayName("the rule is not vacuous — execution really does depend on Flowable")
	void theRuleGovernsSomething() {
		// Without this, deleting every Flowable-touching class would leave the rule
		// above passing and nobody would notice that the adapter had gone. It is the
		// same reasoning as ImportedSetGuard, applied to one rule.
		classes()
				.that().resideInAPackage(EXECUTION)
				.and().haveSimpleName("FlowableProcessEngineGateway")
				.should().dependOnClassesThat().resideInAnyPackage(FLOWABLE)
				.because("if nothing inside execution speaks Flowable any more, the confinement rule "
						+ "above is passing over an empty set and proving nothing")
				.check(OrcaClasses.production());
	}
}
