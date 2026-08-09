package com.lynxis.orca.checks;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Fails the build when a scheduled entry point does not establish an explicit
 * system identity.
 *
 * <p>The requirement is that every scheduled job, relay and reconciler enters a
 * system context. That cannot be proved by a unit test: a unit test proves the
 * mechanism works, while only a rule over every class proves that nobody skipped
 * it, which is the actual claim.
 *
 * <p>Every entry point that runs without a user — a relay, a scheduled job, a
 * reconciler — must enter an explicit system context. Work that runs anonymously
 * cannot be authorised or attributed, and the first time anybody notices is when
 * they are trying to explain a write nobody can account for.
 *
 * <p>This rule was written before the repository had any {@code @Scheduled}
 * methods, rather than added after jobs existed and merely certifying whatever had
 * already been written. It now governs the edge ingest tasks and runtime relay.
 */
class SystemContextRule {

	private static final String SCHEDULED = "org.springframework.scheduling.annotation.Scheduled";
	private static final String SYSTEM_CONTEXT = "com.lynxis.orca.platform.web.system.SystemContext";

	@Test
	@DisplayName("every scheduled entry point enters an explicit system context")
	void scheduledWorkRunsUnderAnIdentity() {
		methods()
				.that().areAnnotatedWith(SCHEDULED)
				.should(enterTheSystemContext())
				.allowEmptyShould(true)
				.because("no path runs with no identity. A scheduled job that runs anonymously "
						+ "cannot be authorised and cannot be attributed, and nothing says so until "
						+ "somebody is trying to explain a write nobody can account for.")
				.check(OrcaClasses.production());
	}

	private static ArchCondition<JavaMethod> enterTheSystemContext() {
		return new ArchCondition<>("call SystemContext.runAs or SystemContext.callAs") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				boolean enters = method.getMethodCallsFromSelf().stream()
						.anyMatch(call -> SYSTEM_CONTEXT.equals(call.getTargetOwner().getFullName())
								&& (call.getName().equals("runAs") || call.getName().equals("callAs")));
				if (!enters) {
					events.add(SimpleConditionEvent.violated(method,
							method.getFullName() + " is @Scheduled but does not enter a SystemContext. "
									+ "Wrap the body in SystemContext.runAs(new SystemIdentity(service, task), ...)."));
				}
			}
		};
	}
}
