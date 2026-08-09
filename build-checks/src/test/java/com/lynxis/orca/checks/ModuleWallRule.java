package com.lynxis.orca.checks;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces module walls: a service may not import another service's internals,
 * and an orca-runtime module may not read another runtime module's tables.
 *
 * <p>The second half is what the {@code api} / {@code domain} / {@code persistence}
 * split is <em>for</em>. It makes the wall expressible as one statement — "no
 * module may reference another module's {@code persistence} package" — rather than
 * as a paragraph in a document nobody reads at three in the afternoon.
 *
 * <p>This is the check orca-runtime's size rests on. It is the largest service by
 * a distance, so its coupling risk is managed structurally through build-enforced
 * module walls rather than developer intention. Without this rule the package
 * split is merely aspirational.
 */
class ModuleWallRule {

	@Test
	@DisplayName("no module reads another module's persistence package")
	void modulesDoNotReadEachOthersTables() {
		for (String owner : OrcaClasses.RUNTIME_MODULES) {
			List<String> othersPersistence = new ArrayList<>();
			for (String other : OrcaClasses.RUNTIME_MODULES) {
				if (!other.equals(owner)) {
					othersPersistence.add(OrcaClasses.ROOT + ".runtime." + other + ".persistence..");
				}
			}
			noClasses()
					.that().resideInAPackage(OrcaClasses.ROOT + ".runtime." + owner + "..")
					.should().dependOnClassesThat()
					.resideInAnyPackage(othersPersistence.toArray(String[]::new))
					// This rule was introduced before the runtime modules held business logic.
					// ArchUnit normally fails a rule that checks nothing, but the wall had to
					// land WITH the module structure rather than after the first class arrived.
					// notify and readmodel are still empty, so the exemption remains;
					// ImportedSetGuard records the exact empty set and deliberate violations
					// during verification proved that the rule fires.
					.allowEmptyShould(true)
					.because("a module reaching into another module's repositories is a module reading "
							+ "another module's tables. The lane monitor legitimately needs running visits "
							+ "beside queued work items, and the answer is readmodel's projection built from "
							+ "both — not a shortcut through persistence.")
					.check(OrcaClasses.production());
		}
	}

	@Test
	@DisplayName("no module reaches into another module's domain package either")
	void modulesDoNotReachIntoEachOthersDomain() {
		// Persistence is the obvious wall. Domain is included because the same defect
		// arrives one layer up: a module that constructs another module's entities is
		// coupled to its schema just as tightly, and it is the shape a developer
		// reaches for when persistence is closed to them.
		for (String owner : OrcaClasses.RUNTIME_MODULES) {
			List<String> othersDomain = new ArrayList<>();
			for (String other : OrcaClasses.RUNTIME_MODULES) {
				if (!other.equals(owner)) {
					othersDomain.add(OrcaClasses.ROOT + ".runtime." + other + ".domain..");
				}
			}
			noClasses()
					.that().resideInAPackage(OrcaClasses.ROOT + ".runtime." + owner + "..")
					.should().dependOnClassesThat()
					.resideInAnyPackage(othersDomain.toArray(String[]::new))
					.allowEmptyShould(true)
					.because("modules talk through their api packages, or through readmodel's projections. "
							+ "§C2's integration seam is narrow on purpose: that narrowness is what makes "
							+ "the partner-facing surface replaceable without touching the engine.")
					.check(OrcaClasses.production());
		}
	}

	@Test
	@DisplayName("no service imports another service's internals")
	void servicesDoNotImportEachOther() {
		for (String service : OrcaClasses.SERVICES) {
			List<String> others = new ArrayList<>();
			for (String other : OrcaClasses.SERVICES) {
				if (!other.equals(service)) {
					others.add(OrcaClasses.ROOT + "." + other + "..");
				}
			}
			noClasses()
					.that().resideInAPackage(OrcaClasses.ROOT + "." + service + "..")
					.should().dependOnClassesThat().resideInAnyPackage(others.toArray(String[]::new))
					.because("services are independently deployable processes. Five mechanisms are permitted "
							+ "between them (§B4) and a Java import is not one of them — a compile-time "
							+ "dependency between two services is a distributed monolith with extra steps.")
					.check(OrcaClasses.production());
		}
	}
}
