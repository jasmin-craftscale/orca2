package com.lynxis.orca.checks;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * A sixth check, beyond the five §5 of the brief tabulates.
 *
 * <p>It is here because P5's second property is stated as a test —
 * <em>"assert every scheduled entry point sets the system context"</em> — and
 * §B10 says the same thing under "Nothing leaks": <em>"assert every scheduled
 * job, relay and reconciler enters it"</em>. That cannot be a unit test. A unit
 * test proves the mechanism works; only a rule over every class proves that
 * nobody skipped it, which is the actual claim.
 *
 * <p>§B6 and §D3: "Every entry point that runs without a user — a relay, a
 * scheduled job, a reconciler — enters an explicit system context. There is no
 * path that runs with no identity at all." Work that runs anonymously cannot be
 * authorised and cannot be attributed, and the first time anybody notices is when
 * they are trying to explain a write nobody can account for.
 *
 * <p>Phase 0 has no {@code @Scheduled} methods, so this rule currently governs an
 * empty set — and it is written now rather than later precisely because a check
 * added after the scheduled jobs exist certifies whatever was written instead of
 * constraining it.
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
				.because("no path runs with no identity (§B6, §D3). A scheduled job that runs anonymously "
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
