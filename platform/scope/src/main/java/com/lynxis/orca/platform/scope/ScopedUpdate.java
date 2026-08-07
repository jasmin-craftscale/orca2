package com.lynxis.orca.platform.scope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A change to existing rows, described rather than written.
 *
 * <p>Like {@link ScopedSelect}, it has nowhere to put a scope predicate: the seam
 * prepends one, and the caller's own filter is ANDed after it. So an update can
 * narrow what it touches and can never widen it.
 *
 * <pre>{@code
 * ScopedUpdate.table("work_item")
 *             .set("status", "DONE")
 *             .scopedBy("site_id")
 *             .where("status = ?", "QUEUED");
 * }</pre>
 *
 * <p><strong>An update that matches nothing is not the same as one the scope
 * forbids.</strong> The first returns zero and is an ordinary outcome — the row
 * had already moved on. The second throws {@link ScopeViolationException}, before
 * any SQL is built, because a caller with no scope for this table is not asking
 * about rows at all.
 *
 * <p>Retirement (§D3 — records are retired rather than removed) is an update, and
 * that is deliberate: it means the one operation that makes a row disappear from
 * every published view acquires the scope predicate like any other write.
 *
 * <h2>Two assignment forms, and exactly two</h2>
 *
 * <p>{@link #set} writes a <em>value</em>. {@link #increment} writes
 * {@code column = column + ?} — the one expression this seam knows how to build.
 *
 * <p><strong>The list is an allow-list, not a starting point.</strong> A general
 * "set this column to this SQL fragment" would put caller-authored SQL into the
 * one place the seam exists to keep it out of, and the scope predicate's guarantee
 * comes from there being no such hole. A second expression form arrives as a second
 * named method with its own property test, or it does not arrive.
 */
public final class ScopedUpdate {

	private final String table;
	private final Map<String, Assignment> assignments = new LinkedHashMap<>();
	private String scopeColumn;
	private String scopeDimension;
	private String filter;
	private final List<Object> filterParameters = new ArrayList<>();

	private ScopedUpdate(String table) {
		this.table = Identifiers.requireTable(table);
	}

	public static ScopedUpdate table(String table) {
		return new ScopedUpdate(table);
	}

	/** A column to change and its new value. Values are bound, never interpolated. */
	public ScopedUpdate set(String column, Object value) {
		return assign(column, value, false);
	}

	/**
	 * Adds to a column's current value, in the database, in one statement:
	 * {@code column = column + ?}.
	 *
	 * <p><strong>This exists because read-write-back loses updates.</strong> Two
	 * callers that each read {@code attempts = 3} and each write {@code 4} record one
	 * failure between them, and there is nothing in either transaction that could
	 * notice. The database is the only place that arithmetic can be made atomic
	 * without a lock the caller has to remember to take.
	 *
	 * <p>The delta is bound like any other value, and the column goes through the
	 * same identifier allow-list as everything else here — so the only SQL this can
	 * produce is {@code <allow-listed column> = <the same column> + ?}.
	 *
	 * <p>A negative delta decrements. Nothing here bounds the result: a counter that
	 * must not go below zero says so in its own {@code where}, because the seam does
	 * not know what a column means.
	 *
	 * @throws IllegalArgumentException if the same column is also {@link #set} —
	 *                                  "assign 5" and "add 5" in one statement is a
	 *                                  question, not an instruction
	 */
	public ScopedUpdate increment(String column, long delta) {
		return assign(column, delta, true);
	}

	private ScopedUpdate assign(String column, Object value, boolean increment) {
		String identifier = Identifiers.require(column, "column");
		Assignment existing = assignments.get(identifier);
		if (existing != null && existing.increment() != increment) {
			throw new IllegalArgumentException("Column '" + identifier + "' is both set and "
					+ "incremented by this update. Those are two different instructions and the "
					+ "seam will not choose between them.");
		}
		assignments.put(identifier, new Assignment(identifier, value, increment));
		return this;
	}

	public ScopedUpdate scopedBy(String column) {
		return scopedBy(column, column);
	}

	public ScopedUpdate scopedBy(String column, String dimension) {
		this.scopeColumn = Identifiers.require(column, "scope column");
		this.scopeDimension = Identifiers.require(dimension, "scope dimension");
		return this;
	}

	/** Additional narrowing. Values are bound; this can only reduce what is touched. */
	public ScopedUpdate where(String sqlFragment, Object... parameters) {
		this.filter = sqlFragment;
		this.filterParameters.clear();
		this.filterParameters.addAll(List.of(parameters));
		return this;
	}

	String table() {
		return table;
	}

	List<Assignment> assignments() {
		if (assignments.isEmpty()) {
			throw new IllegalStateException("This update sets no column. There is nothing to write.");
		}
		return List.copyOf(assignments.values());
	}

	/**
	 * One column's change.
	 *
	 * @param increment {@code true} for {@code column = column + ?}, {@code false}
	 *                  for {@code column = ?}. A boolean rather than an enum because
	 *                  there are two forms and adding a third is a deliberate act
	 *                  that should not be made easy — see the class Javadoc
	 */
	record Assignment(String column, Object value, boolean increment) {
	}

	String scopeColumn() {
		if (scopeColumn == null) {
			throw new IllegalStateException(
					"This update does not say what it is scoped by. Call scopedBy(column) — "
							+ "there is no unscoped write through the seam.");
		}
		return scopeColumn;
	}

	String scopeDimension() {
		scopeColumn();
		return scopeDimension;
	}

	String filter() {
		return filter;
	}

	List<Object> filterParameters() {
		return List.copyOf(filterParameters);
	}
}
