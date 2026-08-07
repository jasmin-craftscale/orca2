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
 */
public final class ScopedUpdate {

	private final String table;
	private final Map<String, Object> assignments = new LinkedHashMap<>();
	private String scopeColumn;
	private String scopeDimension;
	private String filter;
	private final List<Object> filterParameters = new ArrayList<>();

	private ScopedUpdate(String table) {
		this.table = Identifiers.require(table, "table");
	}

	public static ScopedUpdate table(String table) {
		return new ScopedUpdate(table);
	}

	/** A column to change and its new value. Values are bound, never interpolated. */
	public ScopedUpdate set(String column, Object value) {
		assignments.put(Identifiers.require(column, "column"), value);
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

	Map<String, Object> assignments() {
		if (assignments.isEmpty()) {
			throw new IllegalStateException("This update sets no column. There is nothing to write.");
		}
		return Map.copyOf(assignments);
	}

	List<String> assignmentOrder() {
		assignments();
		return List.copyOf(assignments.keySet());
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
