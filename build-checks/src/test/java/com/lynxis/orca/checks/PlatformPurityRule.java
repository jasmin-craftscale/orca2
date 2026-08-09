package com.lynxis.orca.checks;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Enforces platform purity: a class in {@code platform/} may not reference a
 * domain concept or depend on a service.
 *
 * <p>The rule stated as a test you can apply to any class: if it names a
 * <em>visit</em>, a <em>lane</em>, a <em>ticket</em>, a <em>driver</em> or a
 * <em>truck</em>, it does not belong in {@code platform/}. The primitives know
 * about transactions, leases, keys, scopes and HTTP. They do not know what
 * business this is.
 *
 * <p>Why this is the check most worth keeping strict: a shared module that
 * accumulates domain logic becomes the thing every service depends on and nobody
 * can change — which is a well-travelled way for a monorepo to become a
 * distributed monolith.
 */
class PlatformPurityRule {

	/**
	 * The five domain words that the platform is forbidden to name, verbatim.
	 *
	 * <p>Deliberately a word list rather than a package rule. A package rule only
	 * catches a dependency that already exists; this catches the moment somebody
	 * writes {@code VisitId} inside a primitive, which is while it is still cheap.
	 */
	private static final List<String> DOMAIN_WORDS =
			List.of("visit", "lane", "ticket", "driver", "truck");

	@Test
	@DisplayName("no class in platform/ names a visit, a lane, a ticket, a driver or a truck")
	void platformNamesNoDomainConcept() {
		classes()
				.that().resideInAPackage(OrcaClasses.PLATFORM + "..")
				.should(nameNoDomainConcept())
				.because("platform/ holds primitives, never domain. A class there that knows what a visit is "
						+ "belongs in a service — and once every service depends on it, it can no longer "
						+ "be changed.")
				.check(OrcaClasses.production());
	}

	@Test
	@DisplayName("no class in platform/ depends on any service package")
	void platformDependsOnNoService() {
		for (String service : OrcaClasses.SERVICES) {
			noClasses()
					.that().resideInAPackage(OrcaClasses.PLATFORM + "..")
					.should().dependOnClassesThat().resideInAPackage(OrcaClasses.ROOT + "." + service + "..")
					.because("a primitive that depends on a service is not a primitive, and it makes the "
							+ "dependency graph a cycle waiting to be discovered")
					.check(OrcaClasses.production());
		}
	}

	@Test
	@DisplayName("the word list is the one the brief names, and is not quietly shortened")
	void theWordListIsIntact() {
		// A check whose criteria drift is a check that certifies whatever was
		// written. If somebody narrows this list to make a build pass, this fails.
		assertThat(DOMAIN_WORDS).containsExactly("visit", "lane", "ticket", "driver", "truck");
	}

	@Test
	@DisplayName("the matcher itself is right — it catches camelCase and plurals, and does not cry wolf")
	void theMatcherIsCorrect() {
		// The first deliberate violation found this check SILENTLY NOT FIRING: the
		// matcher required a non-letter after the word, so `VisitResponse` — the
		// most likely violation there is — did not match. A rule is only as good as
		// its predicate, so the predicate is tested directly rather than trusted.
		assertThat(namesADomainConcept("VisitResponse")).isTrue();
		assertThat(namesADomainConcept("visitId")).isTrue();
		assertThat(namesADomainConcept("laneCode")).isTrue();
		assertThat(namesADomainConcept("getTickets")).isTrue();
		assertThat(namesADomainConcept("DRIVER_ID")).isTrue();
		assertThat(namesADomainConcept("truck")).isTrue();
		assertThat(namesADomainConcept("visits")).isTrue();

		// ...and does not fire on words that merely contain them.
		assertThat(namesADomainConcept("plane")).isFalse();
		assertThat(namesADomainConcept("translate")).isFalse();
		assertThat(namesADomainConcept("PlanetScale")).isFalse();
		assertThat(namesADomainConcept("OutboxRelay")).isFalse();
		assertThat(namesADomainConcept("ScopedSelect")).isFalse();
	}

	private static ArchCondition<JavaClass> nameNoDomainConcept() {
		return new ArchCondition<>("name no visit, lane, ticket, driver or truck") {
			@Override
			public void check(JavaClass item, ConditionEvents events) {
				flag(item, item.getSimpleName(), "class name", events);
				item.getFields().forEach(field ->
						flag(item, field.getName(), "field '" + field.getName() + "'", events));
				item.getMethods().forEach(method ->
						flag(item, method.getName(), "method '" + method.getName() + "'", events));
			}

			private void flag(JavaClass owner, String name, String what, ConditionEvents events) {
				for (String word : DOMAIN_WORDS) {
					if (namesWord(name, word)) {
						events.add(SimpleConditionEvent.violated(owner,
								owner.getName() + " " + what + " names the domain concept '" + word
										+ "'. platform/ holds primitives, never domain — this belongs "
										+ "in a service."));
						return;
					}
				}
			}
		};
	}

	static boolean namesADomainConcept(String name) {
		return DOMAIN_WORDS.stream().anyMatch(word -> namesWord(name, word));
	}

	/**
	 * Whether {@code name} contains {@code word} as a word.
	 *
	 * <p>Boundaries are camelCase-aware, because Java names are: {@code VisitResponse}
	 * and {@code laneCode} name the concept, {@code plane} and {@code translate} do
	 * not. Getting this wrong in the strict direction is worse than getting it wrong
	 * in the loose one — a check that never fires is indistinguishable from one that
	 * is not wired in, which is how the first version of this shipped.
	 */
	private static boolean namesWord(String name, String word) {
		String haystack = name.toLowerCase(java.util.Locale.ROOT);
		for (String candidate : List.of(word, word + "s")) {
			int from = 0;
			while (true) {
				int at = haystack.indexOf(candidate, from);
				if (at < 0) {
					break;
				}
				if (isBoundaryBefore(name, at) && isBoundaryAfter(name, at + candidate.length())) {
					return true;
				}
				from = at + 1;
			}
		}
		return false;
	}

	private static boolean isBoundaryBefore(String original, int at) {
		if (at == 0) {
			return true;
		}
		char before = original.charAt(at - 1);
		// An underscore, a digit or a case change — DRIVER_ID, getVisit, id2Lane.
		return !Character.isLetter(before) || Character.isUpperCase(original.charAt(at));
	}

	private static boolean isBoundaryAfter(String original, int after) {
		if (after >= original.length()) {
			return true;
		}
		char next = original.charAt(after);
		return !Character.isLetter(next) || Character.isUpperCase(next);
	}
}
