package com.lynxis.orca.platform.scope;

import java.util.List;

import org.springframework.jdbc.core.RowMapper;

/**
 * The one place a query acquires its scope predicate.
 *
 * <p>§B6, and ADR-005: "Scope is enforced in one place, applied by construction,
 * with a build-time check that fails when a query bypasses it. No query carries its
 * own scoping condition."
 *
 * <p>Two halves make that true, and both are needed:
 *
 * <ul>
 *   <li><strong>Applied by construction.</strong> {@link ScopedSelect} has nowhere
 *       to put a scope predicate and refuses to be built without saying which
 *       column it is scoped by. The predicate comes from {@link ScopeContext}, and
 *       from nowhere else.</li>
 *   <li><strong>No way around it.</strong> {@code ScopeSeamRule} in
 *       {@code build-checks} fails the build on any service class that constructs a
 *       query directly — {@code EntityManager.createQuery}, a {@code JdbcTemplate}
 *       read, a raw {@code PreparedStatement}. Without that rule this interface is
 *       a suggestion.</li>
 * </ul>
 *
 * <p><strong>What this is not.</strong> It is not database row-level security, and
 * Phase 0 deliberately does not implement it. That choice belongs to the security
 * design and has a named owner. See {@code README.md} in this module for the trap
 * waiting for whoever implements it.
 */
public interface ScopeSeam {

	/**
	 * Runs a read with the current scope applied.
	 *
	 * <p>With {@link Scope#DENY} in force — which is what "nobody set a scope"
	 * means — this returns an empty list. Never every row.
	 */
	<T> List<T> select(ScopedSelect select, RowMapper<T> mapper);

	/** Counts, with the same predicate. A count that escaped its scope leaks the size of what the caller cannot see. */
	long count(ScopedSelect select);
}
