package com.lynxis.orca.checks;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <strong>Check 3 · Scope seam.</strong> A query constructed outside the seam
 * fails the build.
 *
 * <p>§B6 and ADR-005: <em>"Scope is enforced in one place, applied by
 * construction, with a build-time check that fails when a query bypasses it. No
 * query carries its own scoping condition."</em> The seam is
 * {@code platform/scope}; <strong>this rule is the second half of that sentence,
 * and without it the seam is a suggestion.</strong>
 *
 * <p>The failure it prevents does not look like a failure. A {@code WHERE site_id
 * = ?} written correctly three hundred times and omitted once, in a query added
 * under time pressure a year later, returns another customer's rows with no
 * error, no exception and no log line. The current system's position is roughly
 * 816 hand-written conditions and no single place to fix them.
 *
 * <p><strong>platform/ is exempt, deliberately.</strong> The primitives operate on
 * process-coordination state that has no tenant dimension at all — §C2 says the
 * lease has no {@code site_id} and no row-level-security policy, because every
 * holder runs under the system context. Platform purity is what keeps that
 * exemption honest: a primitive cannot hold tenant data, so it cannot leak it.
 */
class ScopeSeamRule {

	/** Every way a service could reach a database without going through the seam. */
	private static final String[] QUERY_CONSTRUCTION = {
			"jakarta.persistence.EntityManager",
			"jakarta.persistence.EntityManagerFactory",
			"jakarta.persistence.criteria.CriteriaBuilder",
			"org.springframework.jdbc.core.JdbcTemplate",
			"org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
			"org.springframework.jdbc.core.simple.JdbcClient",
			"java.sql.Connection",
			"java.sql.Statement",
			"java.sql.PreparedStatement",
			"javax.sql.DataSource",
	};

	@Test
	@DisplayName("no service class constructs a query itself — every read goes through the scope seam")
	void servicesDoNotConstructQueriesDirectly() {
		for (String service : OrcaClasses.SERVICES) {
			noClasses()
					.that().resideInAPackage(OrcaClasses.ROOT + "." + service + "..")
					.should().dependOnClassesThat().haveNameMatching(anyOf(QUERY_CONSTRUCTION))
					.because("a query built outside platform/scope carries its own scoping condition, or "
							+ "carries none — and the second one returns another tenant's rows with no error, "
							+ "no exception and no log line. Read through ScopeSeam.")
					.check(OrcaClasses.production());
		}
	}

	@Test
	@DisplayName("no service class uses a Spring Data repository interface, which would bypass the seam too")
	void servicesDoNotUseSpringDataRepositoriesDirectly() {
		// Spring Data generates a query from a method name. That query is
		// constructed outside the seam just as surely as a hand-written one, and it
		// is the more likely mistake because it looks like nothing at all.
		for (String service : OrcaClasses.SERVICES) {
			noClasses()
					.that().resideInAPackage(OrcaClasses.ROOT + "." + service + "..")
					.should().beAssignableTo("org.springframework.data.repository.Repository")
					.because("a derived query is still a query, and it acquires no scope predicate. "
							+ "When the security design settles the mechanism, this is the rule that "
							+ "decides whether Spring Data can be admitted behind the seam.")
					.check(OrcaClasses.production());
		}
	}

	/** One regular expression over fully qualified names, dots escaped. */
	private static String anyOf(String... classNames) {
		StringBuilder pattern = new StringBuilder();
		for (String className : classNames) {
			if (!pattern.isEmpty()) {
				pattern.append('|');
			}
			pattern.append(className.replace(".", "\\."));
		}
		return pattern.toString();
	}
}
