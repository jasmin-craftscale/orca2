package com.lynxis.orca.platform.scope;

import java.util.ArrayList;
import java.util.List;

/**
 * A read, described rather than written.
 *
 * <p>A caller states the table, the columns, the scope dimension the table is
 * scoped by, and any additional filter. It does not state the scope predicate —
 * it <em>cannot</em>, because there is nowhere in this type to put one. That is
 * the seam: not a convention that queries should be scoped, but a shape in which
 * an unscoped query is not expressible.
 *
 * <pre>{@code
 * ScopedSelect.from("work_item")
 *             .columns("id", "status")
 *             .scopedBy("site_id")
 *             .where("status = ?", "QUEUED");
 * }</pre>
 *
 * <p>Table and column names are the caller's, so this type stays free of domain
 * knowledge. They are validated as identifiers rather than interpolated blindly —
 * everything reaching SQL from here is either an allow-listed identifier or a
 * bound parameter.
 */
public final class ScopedSelect {

	private final String table;
	private final List<String> columns = new ArrayList<>();
	private String scopeDimension;
	private String scopeColumn;
	private String filter;
	private final List<Object> filterParameters = new ArrayList<>();
	private String orderBy;
	private Integer limit;

	private ScopedSelect(String table) {
		this.table = identifier(table, "table");
	}

	public static ScopedSelect from(String table) {
		return new ScopedSelect(table);
	}

	public ScopedSelect columns(String... names) {
		for (String name : names) {
			columns.add(identifier(name, "column"));
		}
		return this;
	}

	/**
	 * The column this table is scoped by, and the scope dimension it draws from.
	 *
	 * <p>Required. A select that never calls this cannot be built, which is what
	 * makes "every query is scoped" a property of the type rather than of the
	 * reviewer's attention.
	 */
	public ScopedSelect scopedBy(String column) {
		return scopedBy(column, column);
	}

	public ScopedSelect scopedBy(String column, String dimension) {
		this.scopeColumn = identifier(column, "scope column");
		this.scopeDimension = identifier(dimension, "scope dimension");
		return this;
	}

	/** Additional filtering. Values are bound, never interpolated. */
	public ScopedSelect where(String sqlFragment, Object... parameters) {
		this.filter = sqlFragment;
		this.filterParameters.clear();
		this.filterParameters.addAll(List.of(parameters));
		return this;
	}

	public ScopedSelect orderBy(String column) {
		this.orderBy = identifier(column, "order-by column");
		return this;
	}

	public ScopedSelect limit(int limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		this.limit = limit;
		return this;
	}

	String table() {
		return table;
	}

	List<String> selectedColumns() {
		return columns.isEmpty() ? List.of("*") : List.copyOf(columns);
	}

	String scopeColumn() {
		if (scopeColumn == null) {
			throw new IllegalStateException(
					"This select does not say what it is scoped by. Call scopedBy(column) — "
							+ "there is no unscoped read through the seam.");
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

	String orderBy() {
		return orderBy;
	}

	Integer limit() {
		return limit;
	}

	/**
	 * Identifiers are allow-listed by shape rather than escaped.
	 *
	 * <p>These reach SQL as text — there is no way to bind a table name — so the
	 * only safe rule is a narrow one: letters, digits and underscore, starting with
	 * a letter. Anything else is refused rather than quoted, because quoting is
	 * where injection defences go wrong.
	 */
	private static String identifier(String value, String what) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("A " + what + " name is required");
		}
		if (!value.matches("[A-Za-z][A-Za-z0-9_]*")) {
			throw new IllegalArgumentException(
					"Refusing '" + value + "' as a " + what + " name. Identifiers reach SQL as text "
							+ "and are allow-listed by shape: letters, digits and underscore only.");
		}
		return value;
	}
}
