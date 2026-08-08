package com.lynxis.orca.platform.scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import lombok.RequiredArgsConstructor;

/**
 * The one implementation of {@link ScopeSeam}.
 *
 * <p>Every statement it builds begins from the scope and then adds the caller's
 * filter — never the other way round, and never with the scope optional.
 */
@RequiredArgsConstructor
public class JdbcScopeSeam implements ScopeSeam {

	/**
	 * The predicate for a denied scope.
	 *
	 * <p>Written as a constant so it is greppable and so nobody "optimises" it away
	 * by skipping the WHERE clause when there is no scope. Skipping it is the bug:
	 * the query would return everything, and it would look like it was working.
	 */
	private static final String DENY_PREDICATE = "1 = 0";

	private final JdbcTemplate jdbc;

	@Override
	public <T> List<T> select(ScopedSelect select, RowMapper<T> mapper) {
		Statement statement = build(String.join(", ", select.selectedColumns()), select, true);
		return jdbc.query(statement.sql(), mapper, statement.parameters().toArray());
	}

	@Override
	public long count(ScopedSelect select) {
		Statement statement = build("COUNT(*)", select, false);
		Long count = jdbc.queryForObject(statement.sql(), Long.class, statement.parameters().toArray());
		return count == null ? 0L : count;
	}

	@Override
	public int insert(ScopedInsert insert) {
		// The check happens BEFORE any SQL exists. There is no statement to inspect,
		// no predicate to get wrong, and no path where an unpermitted row reaches the
		// database and is then cleaned up.
		requirePermitted(insert.table(), insert.scopeDimension(), insert.scopeValue());
		Statement statement = buildInsert(insert, null);
		return jdbc.update(statement.sql(), statement.parameters().toArray());
	}

	@Override
	public long insertReturningKey(ScopedInsert insert, String keyColumn) {
		requirePermitted(insert.table(), insert.scopeDimension(), insert.scopeValue());
		Statement statement = buildInsert(insert, Identifiers.require(keyColumn, "key column"));
		Long key = jdbc.queryForObject(statement.sql(), Long.class, statement.parameters().toArray());
		if (key == null) {
			// An INSERT ... OUTPUT that returned no row is a database that accepted the
			// write and told us nothing about it. Nothing downstream can proceed on
			// that, and guessing the key is how a row gets attached to the wrong parent.
			throw new IllegalStateException("The insert into " + insert.table()
					+ " returned no value for '" + keyColumn + "'.");
		}
		return key;
	}

	/**
	 * @param outputColumn the column to OUTPUT, or {@code null} for a plain insert.
	 *                     {@code OUTPUT INSERTED.<col>} rather than a separate
	 *                     identity read: {@code SCOPE_IDENTITY()} is per session and
	 *                     silently wrong under a connection pool that hands the next
	 *                     statement a different connection
	 */
	private Statement buildInsert(ScopedInsert insert, String outputColumn) {
		List<String> columns = insert.columnOrder();
		StringBuilder sql = new StringBuilder("INSERT INTO ").append(insert.table()).append(" (");
		sql.append(String.join(", ", columns)).append(valuesClause(outputColumn));
		for (int i = 0; i < columns.size(); i++) {
			sql.append(i == 0 ? "?" : ", ?");
		}
		sql.append(')');

		return new Statement(sql.toString(),
				columns.stream().map(insert.columns()::get).collect(java.util.stream.Collectors.toList()));
	}

	private static String valuesClause(String outputColumn) {
		return outputColumn == null
				? ") VALUES ("
				: ") OUTPUT INSERTED." + outputColumn + " VALUES (";
	}

	@Override
	public int update(ScopedUpdate update) {
		Scope scope = ScopeContext.current();
		Set<String> permitted = permittedFor(scope, update.scopeDimension());
		if (permitted.isEmpty()) {
			// Deliberately not "UPDATE ... WHERE 1 = 0". That would return zero and be
			// indistinguishable from an update whose rows had already moved on.
			throw ScopeViolationException.noScopeFor(update.table(), update.scopeDimension());
		}

		List<ScopedUpdate.Assignment> assignments = update.assignments();
		List<Object> parameters = new ArrayList<>();

		StringBuilder sql = new StringBuilder("UPDATE ").append(update.table()).append(" SET ");
		for (int i = 0; i < assignments.size(); i++) {
			ScopedUpdate.Assignment assignment = assignments.get(i);
			sql.append(i == 0 ? "" : ", ").append(assignment.column()).append(" = ");
			// The ONLY two forms this builds. `column = column + ?` reads the current
			// value inside the same statement, which is what makes it atomic; the
			// column name is the allow-listed identifier, never the caller's string.
			if (assignment.increment()) {
				sql.append(assignment.column()).append(" + ");
			}
			sql.append('?');
			parameters.add(assignment.value());
		}

		// Scope first, caller's filter second, and the filter is ANDed — so it can
		// only ever narrow. There is no arrangement of the caller's fragment that
		// widens the set of rows this statement can reach.
		sql.append(" WHERE ").append(update.scopeColumn()).append(" IN (");
		for (int i = 0; i < permitted.size(); i++) {
			sql.append(i == 0 ? "?" : ", ?");
		}
		sql.append(')');
		parameters.addAll(permitted);

		if (update.filter() != null && !update.filter().isBlank()) {
			sql.append(" AND (").append(update.filter()).append(')');
			parameters.addAll(update.filterParameters());
		}

		return jdbc.update(sql.toString(), parameters.toArray());
	}

	private void requirePermitted(String table, String dimension, Object value) {
		Set<String> permitted = permittedFor(ScopeContext.current(), dimension);
		if (permitted.isEmpty()) {
			throw ScopeViolationException.noScopeFor(table, dimension);
		}
		// Compared as text because Scope is deliberately opaque about what a
		// dimension holds: it does not know that site_id is a number here and a UUID
		// somewhere else, and it must not have to.
		if (value == null || !permitted.contains(String.valueOf(value))) {
			throw ScopeViolationException.valueNotPermitted(table, dimension, value);
		}
	}

	/** The permitted values, or empty for both "denied" and "says nothing about this dimension". */
	private static Set<String> permittedFor(Scope scope, String dimension) {
		return scope.isDeny() ? Set.of() : scope.permitted(dimension);
	}

	private Statement build(String projection, ScopedSelect select, boolean allowOrderAndLimit) {
		Scope scope = ScopeContext.current();
		List<Object> parameters = new ArrayList<>();

		StringBuilder sql = new StringBuilder("SELECT ");
		if (allowOrderAndLimit && select.limit() != null) {
			sql.append("TOP (").append(select.limit()).append(") ");
		}
		sql.append(projection).append(" FROM ").append(select.table());
		if (select.locksMatchedRows()) {
			// UPDLOCK, not a shared lock: a shared lock lets both readers in, which is
			// the case lockMatchedRows() exists to prevent. ROWLOCK keeps the engine
			// from escalating to the table, so one contended key cannot stall the rest.
			sql.append(" WITH (UPDLOCK, ROWLOCK)");
		}
		sql.append(" WHERE ");

		sql.append(scopePredicate(select, scope, parameters));

		if (select.filter() != null && !select.filter().isBlank()) {
			sql.append(" AND (").append(select.filter()).append(')');
			parameters.addAll(select.filterParameters());
		}
		if (allowOrderAndLimit && select.orderBy() != null) {
			sql.append(" ORDER BY ").append(select.orderBy());
			if (select.orderDescending()) {
				// A fixed keyword this class appends — the caller never supplies
				// direction text, so the identifier allow-list stays the whole story.
				sql.append(" DESC");
			}
		}
		return new Statement(sql.toString(), parameters);
	}

	private String scopePredicate(ScopedSelect select, Scope scope, List<Object> parameters) {
		if (scope.isDeny()) {
			return DENY_PREDICATE;
		}
		Set<String> permitted = scope.permitted(select.scopeDimension());
		if (permitted.isEmpty()) {
			// The scope is set, but says nothing about the dimension THIS table is
			// scoped by. That is not permission — it is a scope that does not cover
			// this read, and treating it as unconstrained is precisely the silent
			// widening this seam exists to prevent.
			return DENY_PREDICATE;
		}
		StringBuilder predicate = new StringBuilder(select.scopeColumn()).append(" IN (");
		for (int i = 0; i < permitted.size(); i++) {
			predicate.append(i == 0 ? "?" : ", ?");
		}
		predicate.append(')');
		parameters.addAll(permitted);
		return predicate.toString();
	}

	private record Statement(String sql, List<Object> parameters) {
	}
}
