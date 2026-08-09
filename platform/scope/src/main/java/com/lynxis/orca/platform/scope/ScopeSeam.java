package com.lynxis.orca.platform.scope;

import java.util.List;

import org.springframework.jdbc.core.RowMapper;

/**
 * The one place a query acquires its scope predicate.
 *
 * <p>Scope is enforced here, applied by construction, and protected by a build-time
 * check that fails when a service query bypasses it. No query carries its own
 * scoping condition.
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
 * <p><strong>What this is not.</strong> It is not database row-level security.
 * Choosing such a mechanism remains a security-design decision with a named owner;
 * this interface must not settle it incidentally. See {@code README.md} in this
 * module for the connection-pool trap waiting for whoever implements it.
 *
 * <h2>How a scope comes to be established</h2>
 *
 * <p>The seam applies whatever {@link ScopeContext} carries and never invents one.
 * That leaves exactly two ways for a unit of work to acquire a scope, and both are
 * deliberate acts:
 *
 * <ul>
 *   <li><strong>A request path</strong> derives it at the request boundary, from
 *       the caller's claims and the installation's configuration, and runs the
 *       work inside {@link ScopeContext#callIn}. What that derivation <em>is</em>
 *       — which claim or entitlement it uses, and whether it restricts a site, a
 *       customer or a requester such as a driver — remains a security-design
 *       question. This module takes no position, which is why {@link Scope} is
 *       opaque about what a dimension means.</li>
 *   <li><strong>Background work</strong> — a relay, a scheduled job, a reconciler —
 *       sets the installation's own scope explicitly, inside the system context it
 *       must enter for identity and attribution. <strong>There is no implicit scope
 *       for system work.</strong> Entering {@code SystemContext} grants an
 *       identity, not an entitlement, and the two are deliberately not wired
 *       together: a background job that acquired scope merely by being a
 *       background job would be the silent bypass this seam exists to remove, and
 *       it is the one bypass nobody would ever notice, because system work has no
 *       user to notice it on their behalf.</li>
 * </ul>
 *
 * <p>Work that establishes neither runs under {@link Scope#DENY}: its reads return
 * nothing and its writes are refused outright. That is the intended outcome, not a
 * gap to be smoothed over.
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

	/**
	 * Writes one row, into a scope the caller already holds.
	 *
	 * <p>Refuses with {@link ScopeViolationException} when the current scope
	 * permits nothing in the table's dimension, or when it permits some values and
	 * the row's is not among them. It does not silently write nothing: see
	 * {@link ScopeViolationException} for why writes are loud where reads are
	 * empty.
	 *
	 * @return the number of rows written — one, or an exception
	 */
	int insert(ScopedInsert insert);

	/**
	 * Writes one row and returns the key the database assigned it.
	 *
	 * <p>Same scope check as {@link #insert}, and the same refusal. It exists
	 * separately because the identity of a row a caller has just created is not
	 * recoverable afterwards without a second read — and a second read against a
	 * unique column is a correctness bug the moment two callers insert equal-looking
	 * rows at the same instant.
	 *
	 * <p>The key comes back from the insert itself, in one statement, so there is no
	 * window between creating the row and learning what it is called.
	 *
	 * @param keyColumn the column whose assigned value to return — an identity
	 *                  column, or any column the insert's own statement can output.
	 *                  Allow-listed as an identifier like every other name here
	 * @throws ScopeViolationException as {@link #insert} does
	 */
	long insertReturningKey(ScopedInsert insert, String keyColumn);

	/**
	 * Changes existing rows, never more than the scope permits.
	 *
	 * <p>The scope predicate is applied first and the caller's filter is ANDed
	 * after it, so an update can narrow what it touches and cannot widen it. A
	 * caller with no scope for this table is refused rather than told that zero
	 * rows matched, because those two answers mean entirely different things and
	 * only one of them is worth retrying.
	 *
	 * @return how many rows changed. Zero is an ordinary outcome — the rows had
	 *         already moved on — and is not the same as a refusal
	 */
	int update(ScopedUpdate update);
}
