package com.lynxis.orca.checks;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Enforces contract-first controllers: if a controller implements no generated
 * interface, the build stops.
 *
 * <h2>Why this exists — a rule that was believed to be enforced and was not</h2>
 *
 * <p>A repository audit found that {@code ErrorEnvelopeRule} was credited with
 * enforcing two things: generated interfaces and the shared response envelope.
 * It actually has one test and inspects <strong>return types</strong>; nothing in it
 * asserted that a controller implemented anything at all.
 *
 * <p>The practice held anyway, by convention plus the compiler: once a controller
 * declares {@code implements HealthApi}, a contract change breaks compilation until
 * the implementation matches, which is the point of making the authored OpenAPI
 * document the source of truth. But that
 * consequence is only bought by the {@code implements}, and <strong>nothing stopped a
 * new controller from implementing nothing and still passing every check</strong>,
 * provided it returned the envelope. A hand-written route beside a generated one is
 * invisible in the served document and invisible to every reviewer who trusts the
 * document.
 *
 * <p>So contract-first was a discipline with a compile-time consequence but no
 * enforcement. This class closes that gap by making it a build check.
 *
 * <h2>What counts as generated</h2>
 *
 * <p>An interface in a package ending {@code .api.generated} — where the OpenAPI
 * generator puts them, per every service's {@code build.gradle.kts}. Generated code
 * is never committed, so nothing can be hand-written into that package and survive
 * a clean build: the directory is recreated from the contract every time.
 */
class ContractInterfaceRule {

	private static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
	private static final String GENERATED_API_PACKAGE_SUFFIX = ".api.generated";

	@Test
	@DisplayName("every controller implements an interface generated from its service's contract")
	void controllersImplementTheGeneratedInterface() {
		classes()
				.that().areAnnotatedWith(REST_CONTROLLER)
				.should(implementAGeneratedApiInterface())
				.because("ADR-014 makes the OpenAPI document the source of truth, and the ONLY thing "
						+ "that makes a contract change break the build is the controller implementing "
						+ "the generated interface. A hand-written route beside a generated one is "
						+ "invisible in the served document — see docs/ai-context-report.md §7.1.")
				.check(OrcaClasses.production());
	}

	private static ArchCondition<JavaClass> implementAGeneratedApiInterface() {
		return new ArchCondition<>("implement an interface from its service's generated API package") {
			@Override
			public void check(JavaClass controller, ConditionEvents events) {
				boolean implemented = controller.getAllRawInterfaces().stream()
						.anyMatch(implemented1 -> implemented1.getPackageName()
								.endsWith(GENERATED_API_PACKAGE_SUFFIX));
				if (implemented) {
					return;
				}
				events.add(SimpleConditionEvent.violated(controller,
						controller.getName() + " is a @RestController and implements no interface from "
								+ "a generated API package. Author the route in the service's "
								+ "openapi/orca-<name>.yaml, regenerate, and implement the interface — "
								+ "otherwise the contract and the code are two sources of truth and only "
								+ "one of them is served."));
			}
		};
	}
}
