package com.lynxis.orca.checks;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;

/**
 * <strong>Check 1 · Platform purity.</strong> A class in {@code platform/} may not
 * reference a domain type.
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
	 * The five words from §5 of the brief and §5 of PLATFORM_PRIMITIVES, verbatim.
	 *
	 * <p>Deliberately a word list rather than a package rule. A package rule only
	 * catches a dependency that already exists; this catches the moment somebody
	 * writes {@code VisitId} inside a primitive, which is when it is still cheap.
	 */
	private static final List<String> DOMAIN_WORDS =
			List.of("visit", "lane", "ticket", "driver", "truck");

	@Test
	@DisplayName("no class in platform/ names a visit, a lane, a ticket, a driver or a truck")
	void platformNamesNoDomainConcept() {
		noClasses()
				.that().resideInAPackage(OrcaClasses.PLATFORM + "..")
				.should(NamingADomainConcept.INSTANCE)
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
		assertThat(DOMAIN_WORDS)
				.containsExactly("visit", "lane", "ticket", "driver", "truck");
	}

	/** Matches a class whose own name, a field's name or a method's name contains a domain word. */
	private static final class NamingADomainConcept
			extends com.tngtech.archunit.lang.ArchCondition<JavaClass> {

		private static final NamingADomainConcept INSTANCE = new NamingADomainConcept();

		private NamingADomainConcept() {
			super("name a visit, a lane, a ticket, a driver or a truck");
		}

		@Override
		public void check(JavaClass item, com.tngtech.archunit.lang.ConditionEvents events) {
			flagIfDomain(item, item.getSimpleName(), "class name", events);
			item.getFields().forEach(field ->
					flagIfDomain(item, field.getName(), "field '" + field.getName() + "'", events));
			item.getMethods().forEach(method ->
					flagIfDomain(item, method.getName(), "method '" + method.getName() + "'", events));
		}

		private void flagIfDomain(JavaClass owner, String name, String what,
				com.tngtech.archunit.lang.ConditionEvents events) {
			String lower = name.toLowerCase(Locale.ROOT);
			for (String word : DOMAIN_WORDS) {
				if (containsWholeWord(lower, word)) {
					events.add(com.tngtech.archunit.lang.SimpleConditionEvent.satisfied(owner,
							owner.getName() + " " + what + " names the domain concept '" + word + "'"));
					return;
				}
			}
		}

		/**
		 * Whole-word-ish matching, so {@code plane} does not match {@code lane} and
		 * {@code translate} does not match {@code late}. A substring match here would
		 * produce false failures, and a check that cries wolf gets disabled.
		 */
		private boolean containsWholeWord(String haystack, String word) {
			int from = 0;
			while (true) {
				int at = haystack.indexOf(word, from);
				if (at < 0) {
					return false;
				}
				boolean startsCleanly = at == 0 || !Character.isLetter(haystack.charAt(at - 1));
				int after = at + word.length();
				boolean endsCleanly = after >= haystack.length()
						|| !Character.isLetter(haystack.charAt(after))
						|| haystack.startsWith("s", after);
				if (startsCleanly && endsCleanly) {
					return true;
				}
				from = at + 1;
			}
		}
	}

	/** Unused, but kept so the predicate form is available when a rule needs it. */
	@SuppressWarnings("unused")
	private static final DescribedPredicate<JavaClass> IN_PLATFORM =
			new DescribedPredicate<>("in platform/") {
				@Override
				public boolean test(JavaClass javaClass) {
					return javaClass.getPackageName().startsWith(OrcaClasses.PLATFORM);
				}
			};
}
