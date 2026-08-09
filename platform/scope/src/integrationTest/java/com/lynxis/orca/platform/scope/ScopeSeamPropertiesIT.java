package com.lynxis.orca.platform.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;

/**
 * Proves that a query through the seam with no scope set returns zero rows, never
 * all rows.
 *
 * <p>Property 1 — "a repository method building a query outside the seam fails the
 * build" — cannot be a test, because a test that compiles has already failed to
 * prove it. That one is {@code ScopeSeamRule} in {@code build-checks}, and it is
 * proven by deliberately writing the violation and watching the build fail.
 *
 * <p>Rows are seeded here against a real database because "returns zero rows"
 * needs rows to not return. Asserting on generated SQL would prove the string, not
 * the behaviour.
 */
class ScopeSeamPropertiesIT {

	private static final String SCHEMA = "it_scope";

	private static DataSource dataSource;

	private JdbcTemplate jdbc;
	private ScopeSeam seam;

	@BeforeAll
	static void migrate() {
		// No platform migration here: the seam owns no table. A stand-in for a
		// service's scoped table is created directly, with the column names supplied
		// by the caller — as they are in production, because the seam does not know
		// what a site is.
		dataSource = PlatformDatabase.migratedSchema(SCHEMA);
		new JdbcTemplate(dataSource).execute("""
				IF OBJECT_ID('%s.work_item', 'U') IS NULL
				CREATE TABLE work_item (
					id INT IDENTITY PRIMARY KEY,
					site_id VARCHAR(20) NOT NULL,
					status VARCHAR(20) NOT NULL)
				""".formatted(SCHEMA));
	}

	@BeforeEach
	void seed() {
		jdbc = new JdbcTemplate(dataSource);
		seam = new JdbcScopeSeam(jdbc);
		jdbc.execute("DELETE FROM work_item");
		jdbc.update("INSERT INTO work_item (site_id, status) VALUES ('site-1', 'QUEUED')");
		jdbc.update("INSERT INTO work_item (site_id, status) VALUES ('site-1', 'DONE')");
		jdbc.update("INSERT INTO work_item (site_id, status) VALUES ('site-2', 'QUEUED')");
		jdbc.update("INSERT INTO work_item (site_id, status) VALUES ('site-3', 'QUEUED')");
	}

	@Test
	@DisplayName("with NO scope established, the seam returns zero rows — never all four")
	void anUnsetScopeReturnsNothing() {
		// No ScopeContext.runIn anywhere. This is the background job that forgot, the
		// filter that did not fire, the test harness that never set one up.
		List<String> rows = seam.select(select(), (rs, n) -> rs.getString("site_id"));

		assertThat(rows)
				.as("reading nothing is a bug somebody reports; reading everything is a breach nobody notices")
				.isEmpty();
		assertThat(seam.count(select())).isZero();
		assertThat(everything()).as("the rows are really there — the seam withheld them").hasSize(4);
	}

	@Test
	@DisplayName("Scope.DENY explicitly returns zero rows too")
	void anExplicitDenyReturnsNothing() {
		ScopeContext.runIn(Scope.DENY, () ->
				assertThat(seam.select(select(), (rs, n) -> rs.getString("site_id"))).isEmpty());
	}

	@Test
	@DisplayName("a scope that says nothing about THIS table's dimension is deny, not permission")
	void aScopeMissingThisDimensionIsDeny() {
		// The caller is scoped by carrier — the driver-portal shape — and this table
		// is scoped by site. Treating "no constraint stated" as "no constraint" is
		// the silent widening this seam exists to prevent.
		Scope carrierScope = Scope.of("carrier_id", Set.of("carrier-9"));

		ScopeContext.runIn(carrierScope, () ->
				assertThat(seam.select(select(), (rs, n) -> rs.getString("site_id"))).isEmpty());
	}

	@Test
	@DisplayName("with a scope in force, exactly the permitted rows come back")
	void aScopeReturnsExactlyWhatItPermits() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () -> {
			List<String> rows = seam.select(select(), (rs, n) -> rs.getString("site_id"));
			assertThat(rows).containsOnly("site-1").hasSize(2);
			assertThat(seam.count(select())).isEqualTo(2);
		});

		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1", "site-3")), () ->
				assertThat(seam.select(select(), (rs, n) -> rs.getString("site_id")))
						.containsExactlyInAnyOrder("site-1", "site-1", "site-3"));
	}

	@Test
	@DisplayName("orderByDescending is the bounded 'latest N' read — direction is the seam's keyword, never caller text")
	void descendingOrderIsBoundedAndScoped() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () -> {
			// Newest-first with a limit: the bounded read used by the audit trail.
			// Without a direction the caller would fetch a growing table whole
			// and reverse in memory — an unbounded read dressed as a bounded one.
			List<Integer> newestFirst = seam.select(
					ScopedSelect.from("work_item").columns("id", "site_id").scopedBy("site_id")
							.orderByDescending("id").limit(1),
					(rs, n) -> rs.getInt("id"));
			List<Integer> all = seam.select(
					ScopedSelect.from("work_item").columns("id", "site_id").scopedBy("site_id")
							.orderBy("id"),
					(rs, n) -> rs.getInt("id"));

			assertThat(newestFirst).hasSize(1);
			assertThat(newestFirst.getFirst())
					.as("TOP + DESC returns the LATEST row, not the first")
					.isEqualTo(all.getLast());
			assertThat(all).as("the scope predicate still applies to a descending read").hasSize(2);
		});
	}

	@Test
	@DisplayName("the caller's own filter narrows the scope; it can never widen it")
	void aCallerFilterCannotWidenTheScope() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () -> {
			List<String> rows = seam.select(
					ScopedSelect.from("work_item").columns("site_id").scopedBy("site_id")
							.where("status = ?", "QUEUED"),
					(rs, n) -> rs.getString("site_id"));
			assertThat(rows).containsExactly("site-1");
		});

		// An attempt to write a filter that would widen the result. The scope
		// predicate is ANDed first, so the OR is contained inside the parentheses
		// the seam puts around the caller's fragment.
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () -> {
			List<String> rows = seam.select(
					ScopedSelect.from("work_item").columns("site_id").scopedBy("site_id")
							.where("status = ? OR 1 = 1", "QUEUED"),
					(rs, n) -> rs.getString("site_id"));
			assertThat(rows)
					.as("even a filter designed to match everything cannot escape the scope")
					.containsOnly("site-1");
		});
	}

	@Test
	@DisplayName("the scope is restored afterwards, so a pooled thread does not inherit it")
	void theScopeDoesNotLeak() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () ->
				assertThat(ScopeContext.current().isDeny()).isFalse());

		assertThat(ScopeContext.current().isDeny()).isTrue();
		assertThat(seam.select(select(), (rs, n) -> rs.getString("site_id"))).isEmpty();
	}

	@Test
	@DisplayName("a select that never says what it is scoped by cannot be executed")
	void anUnscopedSelectIsNotExpressible() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () ->
				assertThatThrownBy(() -> seam.select(
						ScopedSelect.from("work_item").columns("site_id"),
						(rs, n) -> rs.getString("site_id")))
						.isInstanceOf(IllegalStateException.class)
						.hasMessageContaining("there is no unscoped read through the seam"));
	}

	@Test
	@DisplayName("identifiers are allow-listed by shape, so a table or column name cannot smuggle SQL")
	void identifiersAreAllowListed() {
		assertThatThrownBy(() -> ScopedSelect.from("work_item; DROP TABLE work_item--"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("allow-listed by shape");
		assertThatThrownBy(() -> ScopedSelect.from("work_item").columns("site_id, 1"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private ScopedSelect select() {
		return ScopedSelect.from("work_item").columns("site_id").scopedBy("site_id");
	}

	private List<String> everything() {
		// Deliberately NOT through the seam — this is the control, proving the rows
		// exist. A build check forbids this shape in service code; this class is the
		// seam's own test.
		return jdbc.queryForList("SELECT site_id FROM work_item", String.class);
	}
}
