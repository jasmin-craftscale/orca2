package com.lynxis.orca.platform.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;

/**
 * <strong>WP2 · the seam's write half.</strong>
 *
 * <p>Phase 0's seam could only read, and the build check forbids a service from
 * touching JDBC — so until now a service physically could not insert a row. That
 * was correct while there was no business logic. The moment there is, the same
 * question arrives from the other side: <em>can a caller write a row into a scope
 * it does not hold?</em>
 *
 * <p>It is the worse half of the problem. A read that escapes its scope shows the
 * caller data they should not see, which at least surfaces somewhere. A write that
 * escapes its scope puts a row where the writer will never look again and where
 * the rightful owner sees it as their own. Nothing reports it, and no later read
 * distinguishes it from data that was always there.
 *
 * <p>Every property below is asserted against real SQL Server with real rows,
 * because "refused" has to mean the row is not in the table afterwards — not that
 * a string was built a particular way.
 */
class ScopeWritePropertiesIT {

	private static final String SCHEMA = "it_scope_write";

	private static DataSource dataSource;

	private JdbcTemplate jdbc;
	private ScopeSeam seam;

	@BeforeAll
	static void migrate() {
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
		jdbc.update("INSERT INTO work_item (site_id, status) VALUES ('site-2', 'QUEUED')");
		jdbc.update("INSERT INTO work_item (site_id, status) VALUES ('site-3', 'QUEUED')");
	}

	// ------------------------------------------------------------------------
	// Property 1 — an unscoped write is REFUSED, not quietly applied to nothing.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("with NO scope established, a write is refused — and refused loudly")
	void anUnsetScopeRefusesTheWrite() {
		// No ScopeContext.runIn anywhere. This is the background job that forgot, the
		// relay nobody scoped, the reconciler added last month.
		assertThatThrownBy(() -> seam.insert(insertFor("site-1")))
				.isInstanceOf(ScopeViolationException.class)
				.hasMessageContaining("permits nothing in dimension 'site_id'");

		assertThatThrownBy(() -> seam.update(retireQueued()))
				.as("an update under no scope must not come back as 'zero rows changed', which is "
						+ "what a successful no-op looks like")
				.isInstanceOf(ScopeViolationException.class);

		assertThat(rowCount()).as("nothing was written").isEqualTo(3);
	}

	@Test
	@DisplayName("entering a system identity is not the same as holding a scope — there is no implicit one")
	void systemWorkGetsNoImplicitScope() {
		// The rule the seam's Javadoc states, made executable. Background work is
		// required to enter a system context (§B6) — and that grants an IDENTITY, not
		// an ENTITLEMENT. Wiring the two together would be the one silent bypass
		// nobody would ever notice, because system work has no user to notice on its
		// behalf. Here, "background work" is simply a thread that set no scope.
		assertThat(ScopeContext.current().isDeny())
				.as("the default is deny, for system work exactly as for anything else")
				.isTrue();

		assertThatThrownBy(() -> seam.insert(insertFor("site-1")))
				.isInstanceOf(ScopeViolationException.class);

		// And when it sets one deliberately, it writes.
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")),
				() -> assertThat(seam.insert(insertFor("site-1"))).isEqualTo(1));
		assertThat(rowCount()).isEqualTo(4);
	}

	@Test
	@DisplayName("an explicit DENY refuses too — it is not a special 'unset' case that behaves differently")
	void explicitDenyRefusesTheWrite() {
		ScopeContext.runIn(Scope.DENY, () -> {
			assertThatThrownBy(() -> seam.insert(insertFor("site-1")))
					.isInstanceOf(ScopeViolationException.class);
			assertThatThrownBy(() -> seam.update(retireQueued()))
					.isInstanceOf(ScopeViolationException.class);
		});
		assertThat(rowCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("a scope that says nothing about THIS dimension is deny, not permission")
	void aScopeInAnotherDimensionIsNotPermission() {
		// The caller holds a real scope — over carriers. It says nothing about sites,
		// and "says nothing" must not read as "unconstrained". This is the silent
		// widening the whole primitive exists to prevent, and it is the one that
		// looks most like working code.
		ScopeContext.runIn(Scope.of("carrier_id", Set.of("carrier-9")), () -> {
			assertThatThrownBy(() -> seam.insert(insertFor("site-1")))
					.isInstanceOf(ScopeViolationException.class)
					.hasMessageContaining("dimension 'site_id'");
			assertThatThrownBy(() -> seam.update(retireQueued()))
					.isInstanceOf(ScopeViolationException.class);
		});
		assertThat(rowCount()).isEqualTo(3);
	}

	// ------------------------------------------------------------------------
	// Property 2 — a permitted write lands; an unpermitted VALUE does not.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("a write into a scope the caller holds lands")
	void aPermittedWriteLands() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")),
				() -> assertThat(seam.insert(insertFor("site-1"))).isEqualTo(1));

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM work_item WHERE site_id = 'site-1' AND status = 'NEW'", Long.class))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("a caller scoped to site-1 cannot create a row in site-2 — the row it would never see again")
	void aWriteIntoAnotherScopeIsRefused() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () ->
				assertThatThrownBy(() -> seam.insert(insertFor("site-2")))
						.isInstanceOf(ScopeViolationException.class)
						.hasMessageContaining("does not permit the value 'site-2'"));

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM work_item WHERE site_id = 'site-2'", Long.class))
				.as("site-2 has exactly the row it started with")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("a caller holding two sites may write into either, and into no third")
	void aMultiSiteScopePermitsExactlyItsSites() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1", "site-2")), () -> {
			assertThat(seam.insert(insertFor("site-1"))).isEqualTo(1);
			assertThat(seam.insert(insertFor("site-2"))).isEqualTo(1);
			assertThatThrownBy(() -> seam.insert(insertFor("site-3")))
					.isInstanceOf(ScopeViolationException.class);
		});
		assertThat(rowCount()).isEqualTo(5);
	}

	// ------------------------------------------------------------------------
	// Property 3 — an update cannot reach beyond the scope, however it is filtered.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("an update touches only rows inside the scope, however broadly it is written")
	void anUpdateCannotReachOutsideTheScope() {
		int changed = ScopeContext.callIn(Scope.of("site_id", Set.of("site-1")),
				() -> seam.update(retireQueued()));

		assertThat(changed).as("one row — site-1's").isEqualTo(1);
		assertThat(statusOf("site-1")).isEqualTo("RETIRED");
		assertThat(statusOf("site-2")).as("untouched, and the caller was never told it existed").isEqualTo("QUEUED");
		assertThat(statusOf("site-3")).isEqualTo("QUEUED");
	}

	@Test
	@DisplayName("a caller filter of 'status = ? OR 1 = 1' still cannot escape the scope")
	void aCallerFilterCannotWidenTheScope() {
		// The filter is ANDed AFTER the scope predicate, so the most permissive
		// fragment a caller can write still intersects with what they hold. The same
		// property the read half proves, from the side where getting it wrong writes
		// rather than reads.
		int changed = ScopeContext.callIn(Scope.of("site_id", Set.of("site-1")),
				() -> seam.update(ScopedUpdate.table("work_item")
						.set("status", "RETIRED")
						.scopedBy("site_id")
						.where("status = ? OR 1 = 1", "QUEUED")));

		assertThat(changed).isEqualTo(1);
		assertThat(statusOf("site-2")).isEqualTo("QUEUED");
		assertThat(statusOf("site-3")).isEqualTo("QUEUED");
	}

	@Test
	@DisplayName("zero rows changed is an ordinary outcome, and is NOT a refusal")
	void changingNothingIsNotTheSameAsBeingRefused() {
		// The distinction the exception exists to preserve. Here the caller holds the
		// scope and the rows simply do not match — that is a number, not an error.
		int changed = ScopeContext.callIn(Scope.of("site_id", Set.of("site-1")),
				() -> seam.update(ScopedUpdate.table("work_item")
						.set("status", "RETIRED")
						.scopedBy("site_id")
						.where("status = ?", "ALREADY_GONE")));

		assertThat(changed).isZero();
	}

	// ------------------------------------------------------------------------
	// Property 4 — an unscoped write is not expressible, and identifiers are shapes.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("a write that never says what it is scoped by cannot be built")
	void anUnscopedWriteIsNotExpressible() {
		assertThatThrownBy(() -> seam.insert(ScopedInsert.into("work_item")
				.value("site_id", "site-1").value("status", "NEW")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("no unscoped write through the seam");

		assertThatThrownBy(() -> seam.update(ScopedUpdate.table("work_item").set("status", "X")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("no unscoped write through the seam");
	}

	@Test
	@DisplayName("an insert scoped by a column it supplies no value for is refused — the seam will not pick a scope")
	void anInsertMustSayWhichScopeTheRowLandsIn() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () ->
				assertThatThrownBy(() -> seam.insert(ScopedInsert.into("work_item")
						.scopedBy("site_id")
						.value("status", "NEW")))
						.as("defaulting it — to the only permitted site, say — would be the seam "
								+ "deciding what the caller is entitled to")
						.isInstanceOf(IllegalStateException.class)
						.hasMessageContaining("supplies no value"));

		assertThat(rowCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("identifiers are allow-listed by shape on the write side too, not just the read side")
	void identifiersAreAllowListedOnWrites() {
		assertThatThrownBy(() -> ScopedInsert.into("work_item; DROP TABLE work_item"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ScopedInsert.into("work_item").value("status --", "x"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ScopedUpdate.table("work_item").set("status", "x").scopedBy("site_id)"))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(rowCount()).isEqualTo(3);
	}

	// ------------------------------------------------------------------------
	// WP6 · the two things the seam had to learn for admission.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("insertReturningKey gives back the assigned key — and is refused by scope exactly as insert is")
	void theAssignedKeyComesBackFromTheInsertItself() {
		long key = ScopeContext.callIn(Scope.of("site_id", Set.of("site-1")),
				() -> seam.insertReturningKey(insertFor("site-1"), "id"));

		assertThat(key)
				.as("the identity of the row this caller just created, from the insert's own "
						+ "statement — reading it back afterwards against a non-unique column is a "
						+ "correctness bug the moment two callers insert equal-looking rows")
				.isEqualTo(jdbc.queryForObject(
						"SELECT MAX(id) FROM work_item WHERE site_id = 'site-1'", Long.class));

		// The scope check is the same one, in the same place. A second write surface
		// with a weaker check is how the seam acquires a hole.
		assertThatThrownBy(() -> ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")),
				() -> seam.insertReturningKey(insertFor("site-2"), "id")))
				.isInstanceOf(ScopeViolationException.class);
		assertThatThrownBy(() -> seam.insertReturningKey(insertFor("site-1"), "id"))
				.as("and with no scope at all it is refused, not applied")
				.isInstanceOf(ScopeViolationException.class);

		assertThat(rowCount()).isEqualTo(4);
	}

	@Test
	@DisplayName("lockMatchedRows actually serialises two readers — a shared lock would let both in")
	void aLockedReadHoldsOffTheSecondReaderUntilCommit() throws Exception {
		// The property the lane lock rests on, asserted on the seam rather than on
		// the caller: two threads read-decide-write the same row, and if BOTH reads
		// can proceed the decision is made twice. A plain read passes that test; only
		// an exclusive one does not.
		java.util.concurrent.CountDownLatch firstHasTheLock = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch firstMayCommit = new java.util.concurrent.CountDownLatch(1);
		java.util.List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();

		org.springframework.transaction.support.TransactionTemplate transactions =
				new org.springframework.transaction.support.TransactionTemplate(
						new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));

		try (java.util.concurrent.ExecutorService both =
				java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {

			both.submit(() -> ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () ->
					transactions.executeWithoutResult(status -> {
						seam.select(lockedRead(), (rs, row) -> rs.getInt("id"));
						order.add("first-read");
						firstHasTheLock.countDown();
						await(firstMayCommit);
						order.add("first-commit");
					})));

			assertThat(firstHasTheLock.await(2, java.util.concurrent.TimeUnit.MINUTES)).isTrue();

			java.util.concurrent.Future<?> second = both.submit(() ->
					ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () ->
							transactions.executeWithoutResult(status -> {
								seam.select(lockedRead(), (rs, row) -> rs.getInt("id"));
								order.add("second-read");
							})));

			// Long enough that a second read which was going to proceed has done so.
			Thread.sleep(1_000);
			assertThat(order)
					.as("if 'second-read' is here, the lock is shared and two instances are both "
							+ "about to decide that no visit is running on this lane")
					.containsExactly("first-read");

			firstMayCommit.countDown();
			second.get(2, java.util.concurrent.TimeUnit.MINUTES);
		}

		assertThat(order).containsExactly("first-read", "first-commit", "second-read");
	}

	private static ScopedSelect lockedRead() {
		return ScopedSelect.from("work_item")
				.columns("id")
				.scopedBy("site_id")
				.where("status = ?", "QUEUED")
				.lockMatchedRows();
	}

	private static void await(java.util.concurrent.CountDownLatch latch) {
		try {
			if (!latch.await(2, java.util.concurrent.TimeUnit.MINUTES)) {
				throw new IllegalStateException("the other thread never arrived");
			}
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}

	// ------------------------------------------------------------------------

	private static ScopedInsert insertFor(String site) {
		return ScopedInsert.into("work_item")
				.scopedBy("site_id")
				.value("site_id", site)
				.value("status", "NEW");
	}

	private static ScopedUpdate retireQueued() {
		return ScopedUpdate.table("work_item")
				.set("status", "RETIRED")
				.scopedBy("site_id")
				.where("status = ?", "QUEUED");
	}

	private long rowCount() {
		Long count = jdbc.queryForObject("SELECT COUNT(*) FROM work_item", Long.class);
		return count == null ? 0 : count;
	}

	private String statusOf(String site) {
		return jdbc.queryForObject("SELECT status FROM work_item WHERE site_id = ?", String.class, site);
	}
}
