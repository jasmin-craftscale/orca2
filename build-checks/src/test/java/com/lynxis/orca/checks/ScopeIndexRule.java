package com.lynxis.orca.checks;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <strong>Check 8 · The scope-predicate lock trap.</strong> A scoped table with no
 * index leading with the scope column, and the build stops.
 *
 * <h2>The failure this exists to prevent, which already happened once</h2>
 *
 * <p>Every read through {@code platform/scope}'s seam leads with the scope
 * predicate — {@code WHERE site_external_id IN (…) AND <the rest>}. That is
 * deny-by-default working as designed, and it means the seam decides the leading
 * column of every query shape in the product, whether or not the author of a
 * migration was thinking about it.
 *
 * <p>WP6 found what follows from that. {@code runtime.lane_session} was keyed on
 * {@code lane_id} alone, which does not match the leading column of the predicate,
 * so on a table with a handful of rows SQL Server answered with a <strong>clustered
 * index scan</strong> — and under the {@code UPDLOCK} the lane lock exists to
 * provide, a scan takes an update lock on <strong>every lane at the site</strong>.
 * The eight-lane run deadlocked repeatedly and exhausted its retries. It was fixed
 * by widening the key to {@code (site_external_id, lane_id)}, in that order.
 *
 * <p>{@code phase-1-report.md} §5.13 records it and says the important part out
 * loud: <em>this is a general trap the seam creates, not a one-off — any table whose
 * hot access path does not lead with the scope column will silently do this, and
 * nothing in Java can see it.</em> Nothing in Java, but something in the migrations,
 * which is what this reads.
 *
 * <h2>What it enforces</h2>
 *
 * <p>For every table declaring the scope column, <strong>some</strong> index must
 * lead with it — the primary key, a unique index, or an ordinary one. Any of the
 * three makes a seek possible; none of them makes it certain.
 *
 * <p>⚠️ <strong>Necessary, not sufficient, and stated that way on purpose.</strong>
 * The optimizer can still choose a scan on a small table, and no build check can
 * read a query plan. What this rule removes is the case where a seek was never
 * available at all — which is the one that produced the deadlock, and the one a
 * migration author can be held to.
 *
 * <h2>⚠️ The scope dimension is named here, and there is exactly one today</h2>
 *
 * <p>{@code site_external_id} is the dimension every service scopes on (the phase-1
 * report §5.7 records why edge uses the <em>external</em> id). If a second dimension
 * is ever introduced, <strong>it must be added to {@link #SCOPE_COLUMNS}</strong> —
 * and nothing automated will tell you, because a dimension that exists only inside a
 * {@code Scope.of(…)} call is not something this file can discover. That is the
 * known limit of this rule, recorded rather than hidden.
 */
class ScopeIndexRule {

	/** ⚠️ The scope dimensions. One today. See the class Javadoc before adding one. */
	private static final List<String> SCOPE_COLUMNS = List.of("site_external_id");

	private static final Pattern CREATE_TABLE =
			Pattern.compile("\\bCREATE\\s+TABLE\\s+([A-Za-z0-9_\\[\\]\\.]+)\\s*\\(", Pattern.CASE_INSENSITIVE);

	private static final Pattern CREATE_INDEX = Pattern.compile(
			"\\bCREATE\\s+(?:UNIQUE\\s+)?(?:CLUSTERED\\s+|NONCLUSTERED\\s+)?INDEX\\s+[A-Za-z0-9_\\[\\]]+"
					+ "\\s+ON\\s+([A-Za-z0-9_\\[\\]\\.]+)\\s*\\(([^)]*)\\)",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern PRIMARY_KEY =
			Pattern.compile("\\bPRIMARY\\s+KEY\\b(?:\\s+(?:CLUSTERED|NONCLUSTERED))?\\s*\\(([^)]*)\\)",
					Pattern.CASE_INSENSITIVE);

	@Test
	@DisplayName("every scoped table has an index leading with the scope column")
	void everyScopedTableCanBeSoughtByItsScopePredicate() {
		List<Table> tables = tables();
		Map<String, List<String>> leadingIndexColumns = indexLeadingColumns();

		List<String> violations = new ArrayList<>();
		List<Table> governed = tables.stream().filter(Table::isScoped).toList();

		for (Table table : governed) {
			boolean seekable = SCOPE_COLUMNS.stream().anyMatch(scope ->
					scope.equals(first(table.primaryKey))
							|| leadingIndexColumns.getOrDefault(table.name, List.of()).contains(scope));
			if (!seekable) {
				violations.add(("%s (%s) declares a scope column but no index leads with one. "
						+ "Its primary key is (%s) and its indexes lead with %s. Every seam read leads "
						+ "with the scope predicate, so this table can only be SCANNED — and a scan "
						+ "under UPDLOCK locks every row at the site. Add the scope column as the "
						+ "LEADING column of the key or of an index.")
						.formatted(table.name, table.file,
								String.join(", ", table.primaryKey),
								leadingIndexColumns.getOrDefault(table.name, List.of())));
			}
		}

		assertThat(violations)
				.as("phase-1-report.md §5.13: a table whose hot access path does not lead with the "
						+ "scope column deadlocks the lane, silently, and nothing in Java can see it")
				.isEmpty();

		// A rule that read no migrations passes. Same reasoning as ImportedSetGuard.
		assertThat(governed)
				.as("no scoped tables were found at all — this rule is reading the wrong files and "
						+ "would pass over anything")
				.hasSizeGreaterThanOrEqualTo(6);
	}

	@Test
	@DisplayName("the parser actually parses — it sees Flowable's 45 tables as well as ours")
	void theParserSeesWhatTheMigrationsContain() {
		List<Table> tables = tables();

		// The floor is deliberately high enough to include Flowable's own schema. If
		// the CREATE TABLE pattern ever stops matching, THIS is what says so — the
		// rule above would simply find nothing to govern and pass.
		assertThat(tables)
				.as("the migration parser found almost nothing, so the rule above is inspecting an "
						+ "empty set and proving nothing")
				.hasSizeGreaterThanOrEqualTo(50);

		assertThat(tables.stream().map(table -> table.name).toList())
				.as("the tables the phase-1 report names as scoped must all be seen")
				.contains("lane_session", "execution", "event_buffer", "command_log");

		assertThat(tables.stream().filter(table -> table.name.equals("lane_session")).findFirst())
				.get()
				.satisfies(laneSession -> assertThat(first(laneSession.primaryKey))
						.as("the key order that fixed the WP6 deadlock, read back out of the migration")
						.isEqualTo("site_external_id"));
	}

	// ------------------------------------------------------------------------

	private static List<Table> tables() {
		List<Table> tables = new ArrayList<>();
		for (Path migration : RepositoryFiles.migrations()) {
			String sql = withoutComments(RepositoryFiles.read(migration));
			Matcher creation = CREATE_TABLE.matcher(sql);
			while (creation.find()) {
				String body = balancedBody(sql, creation.end() - 1);
				if (body == null) {
					continue;
				}
				tables.add(new Table(unquote(creation.group(1)), columnsOf(body), primaryKeyOf(body),
						RepositoryFiles.root().relativize(migration).toString()));
			}
		}
		return tables;
	}

	/** Every index in the corpus, as table → the leading column of each index on it. */
	private static Map<String, List<String>> indexLeadingColumns() {
		Map<String, List<String>> leading = new LinkedHashMap<>();
		for (Path migration : RepositoryFiles.migrations()) {
			Matcher index = CREATE_INDEX.matcher(withoutComments(RepositoryFiles.read(migration)));
			while (index.find()) {
				String table = unquote(index.group(1));
				String firstColumn = unquote(index.group(2).split(",")[0].trim().split("\\s+")[0]);
				leading.computeIfAbsent(table, key -> new ArrayList<>()).add(firstColumn);
			}
		}
		return leading;
	}

	/** The parenthesised body of a CREATE TABLE, by balancing from its opening bracket. */
	private static String balancedBody(String sql, int openIndex) {
		int depth = 0;
		for (int i = openIndex; i < sql.length(); i++) {
			char character = sql.charAt(i);
			if (character == '(') {
				depth++;
			}
			else if (character == ')') {
				depth--;
				if (depth == 0) {
					return sql.substring(openIndex + 1, i);
				}
			}
		}
		return null;
	}

	private static List<String> columnsOf(String body) {
		return topLevelItems(body).stream()
				.filter(item -> !item.toUpperCase(Locale.ROOT).startsWith("CONSTRAINT")
						&& !item.toUpperCase(Locale.ROOT).startsWith("PRIMARY KEY")
						&& !item.toUpperCase(Locale.ROOT).startsWith("UNIQUE")
						&& !item.toUpperCase(Locale.ROOT).startsWith("FOREIGN KEY")
						&& !item.toUpperCase(Locale.ROOT).startsWith("CHECK"))
				.map(item -> unquote(item.split("\\s+")[0]))
				.toList();
	}

	/** The key columns, in order. An inline {@code PRIMARY KEY} on a column counts. */
	private static List<String> primaryKeyOf(String body) {
		for (String item : topLevelItems(body)) {
			Matcher declared = PRIMARY_KEY.matcher(item);
			if (declared.find()) {
				return java.util.Arrays.stream(declared.group(1).split(","))
						.map(column -> unquote(column.trim().split("\\s+")[0]))
						.toList();
			}
			if (item.toUpperCase(Locale.ROOT).matches(".*\\bPRIMARY\\s+KEY\\b.*")) {
				return List.of(unquote(item.split("\\s+")[0]));
			}
		}
		return List.of();
	}

	/** Splits a table body on commas that are not inside brackets. */
	private static List<String> topLevelItems(String body) {
		List<String> items = new ArrayList<>();
		int depth = 0;
		StringBuilder current = new StringBuilder();
		for (char character : body.toCharArray()) {
			if (character == '(') {
				depth++;
			}
			if (character == ')') {
				depth--;
			}
			if (character == ',' && depth == 0) {
				items.add(current.toString().trim());
				current.setLength(0);
				continue;
			}
			current.append(character);
		}
		items.add(current.toString().trim());
		return items.stream().map(item -> item.replaceAll("\\s+", " ").trim())
				.filter(item -> !item.isEmpty()).toList();
	}

	private static String withoutComments(String sql) {
		return sql.replaceAll("(?m)--.*$", "");
	}

	private static String unquote(String identifier) {
		String bare = identifier.replace("[", "").replace("]", "").replace("\"", "");
		int lastDot = bare.lastIndexOf('.');
		return (lastDot < 0 ? bare : bare.substring(lastDot + 1)).toLowerCase(Locale.ROOT);
	}

	private static String first(List<String> columns) {
		return columns.isEmpty() ? null : columns.getFirst();
	}

	private record Table(String name, List<String> columns, List<String> primaryKey, String file) {

		boolean isScoped() {
			return columns.stream().anyMatch(SCOPE_COLUMNS::contains);
		}
	}
}
