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

	private Statement build(String projection, ScopedSelect select, boolean allowOrderAndLimit) {
		Scope scope = ScopeContext.current();
		List<Object> parameters = new ArrayList<>();

		StringBuilder sql = new StringBuilder("SELECT ");
		if (allowOrderAndLimit && select.limit() != null) {
			sql.append("TOP (").append(select.limit()).append(") ");
		}
		sql.append(projection).append(" FROM ").append(select.table()).append(" WHERE ");

		sql.append(scopePredicate(select, scope, parameters));

		if (select.filter() != null && !select.filter().isBlank()) {
			sql.append(" AND (").append(select.filter()).append(')');
			parameters.addAll(select.filterParameters());
		}
		if (allowOrderAndLimit && select.orderBy() != null) {
			sql.append(" ORDER BY ").append(select.orderBy());
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
