package com.lynxis.orca.platform.scope;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A row to be written, described rather than assembled.
 *
 * <p>The mirror of {@link ScopedSelect}, and it withholds the same thing: there is
 * nowhere in this type to say <em>which scope the row belongs to</em> independently
 * of the value being written. The scope column is one of the columns being
 * inserted, and the seam checks that its value is one the current {@link Scope}
 * permits before the statement is built.
 *
 * <pre>{@code
 * ScopedInsert.into("work_item")
 *             .scopedBy("site_id")
 *             .value("site_id", "site-1")
 *             .value("status", "QUEUED");
 * }</pre>
 *
 * <p>That check is the whole difference between this and a convenience wrapper
 * around {@code INSERT}. Without it, a caller entitled to read one site could
 * create rows in another and never see them again — a write nobody can find is
 * worse than a read that returns too much, because the read at least shows up
 * somewhere.
 *
 * <p><strong>There is no delete.</strong> Ordinary records are retired rather than
 * removed; only a retention policy deletes them deliberately, and retention is the
 * outbox's and the purge job's business, not a caller's. A retirement is an
 * {@link ScopedUpdate}, so it acquires the scope predicate like any other write.
 */
public final class ScopedInsert {

	private final String table;
	private final Map<String, Object> columns = new LinkedHashMap<>();
	private String scopeColumn;
	private String scopeDimension;

	private ScopedInsert(String table) {
		this.table = Identifiers.requireTable(table);
	}

	public static ScopedInsert into(String table) {
		return new ScopedInsert(table);
	}

	/** A column and its value. The value is bound, never interpolated. */
	public ScopedInsert value(String column, Object value) {
		columns.put(Identifiers.require(column, "column"), value);
		return this;
	}

	/**
	 * The column this table is scoped by, and the scope dimension it draws from.
	 *
	 * <p>Required, and — unlike a read — it must also appear among the values,
	 * because a row has to land in some scope and the seam will not choose one for
	 * it.
	 */
	public ScopedInsert scopedBy(String column) {
		return scopedBy(column, column);
	}

	public ScopedInsert scopedBy(String column, String dimension) {
		this.scopeColumn = Identifiers.require(column, "scope column");
		this.scopeDimension = Identifiers.require(dimension, "scope dimension");
		return this;
	}

	String table() {
		return table;
	}

	/**
	 * ⚠️ Not {@code Map.copyOf}, and that is a fix rather than a style choice.
	 *
	 * <p>{@code Map.copyOf} rejects a null <em>value</em> with a bare
	 * {@link NullPointerException} out of {@code ImmutableCollections}, which says
	 * nothing about columns, inserts or scope. A nullable column is entirely
	 * ordinary — a device that did not identify itself, a command with no
	 * parameters, an event with no attributes — and a seam that cannot express
	 * {@code NULL} pushes every such write back to raw JDBC, which the build check
	 * correctly forbids.
	 *
	 * <p>The defect remained latent until the first production-shaped insert supplied
	 * a genuinely absent value. The history is recorded here because changing back
	 * to {@code Map.copyOf} would silently restore the pressure to bypass the seam.
	 */
	Map<String, Object> columns() {
		return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(columns));
	}

	java.util.List<String> columnOrder() {
		return java.util.List.copyOf(columns.keySet());
	}

	String scopeColumn() {
		if (scopeColumn == null) {
			throw new IllegalStateException(
					"This insert does not say what it is scoped by. Call scopedBy(column) — "
							+ "there is no unscoped write through the seam.");
		}
		return scopeColumn;
	}

	String scopeDimension() {
		scopeColumn();
		return scopeDimension;
	}

	/** The value this row would land under, or an error if the insert never supplies one. */
	Object scopeValue() {
		String column = scopeColumn();
		if (!columns.containsKey(column)) {
			throw new IllegalStateException(
					"This insert is scoped by '" + column + "' but supplies no value for it. "
							+ "A row has to land in some scope, and the seam will not pick one: "
							+ "defaulting it would be the seam deciding what the caller is entitled to.");
		}
		return columns.get(column);
	}
}
